// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.NativeApiTests.CHAT_OUT;
import static gg.tame.conduit.tests.NativeApiTests.DISCONNECT_OUT;
import static gg.tame.conduit.tests.NativeApiTests.chat;
import static gg.tame.conduit.tests.NativeApiTests.id;
import static gg.tame.conduit.tests.NativeApiTests.require;
import static gg.tame.conduit.tests.NativeApiTests.text;
import static gg.tame.conduit.tests.NativeApiTests.waitFor;

import gg.tame.conduit.api.event.player.PlayerKickedFromServerEvent;
import gg.tame.conduit.api.event.player.PlayerKickedFromServerEvent.KickResult;
import gg.tame.conduit.api.event.player.PlayerServerConnectedEvent;
import gg.tame.conduit.api.event.player.PlayerServerSwitchFailedEvent;
import gg.tame.conduit.api.player.ConnectResult;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.api.server.RegisteredServer;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.HealthSettings;
import gg.tame.conduit.health.BackendHealth;
import gg.tame.conduit.health.BackendHealthService;
import gg.tame.conduit.routing.BackendSelector;
import gg.tame.conduit.routing.ServerRegistry;
import gg.tame.conduit.session.TrackedPlayer;
import gg.tame.conduit.tests.NativeApiTests.Backend;
import gg.tame.conduit.tests.NativeApiTests.Client;
import gg.tame.conduit.tests.NativeApiTests.Fixture;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Routing under failure, against scripted 1.8.9 backends: what a player is sent to, what is tried
 * and how often, what they are told, and what health state a failure leaves behind.
 */
public final class RoutingFailoverTests {
  public static void main(String[] arguments) throws Exception { run(); }

  /** Probes enabled, but none due while a test runs: health moves only by what the test does. */
  private static final HealthSettings PASSIVE_ONLY = new HealthSettings(true, 60_000, 1_500, 2, 2);
  private static final String WHITELIST = "{\"text\":\"You are not whitelisted\"}";

  public static void run() throws Exception {
    aRedirectToAServerThatRefusedIsNotTriedAgain();
    aLostBackendWithNowhereToGoSaysWhy();
    everyBackendDownGivesOneMessageAndFreesTheSlot();
    connectFailuresCountAgainstHealthWhileChecksRun();
    serversRegisteredAtRuntimeAreHealthCheckedAndForgotten();
    aConfiguredServerUnregisteredAtRuntimeLeavesJoinsWorking();
    disablingHealthChecksForgetsTheirVerdicts();
    aSwitchToAServerKnownDownFailsAtOnce();
    hubPrefersALobbyHealthChecksAllow();
    System.out.println("RoutingFailoverTests OK");
  }

  /**
   * A listener that sends every refused player to the lobby -- a common way to write "kick to hub" --
   * met a lobby that refuses them. The walk put the lobby back in front of itself after each refusal,
   * and logged the player in to it again, forever, on the join thread and on the fallback path.
   */
  private static void aRedirectToAServerThatRefusedIsNotTriedAgain() throws Exception {
    try (Backend lobby = new Backend("lobby"); Backend survival = new Backend("survival");
         Fixture proxy = new Fixture(List.of(lobby, survival), List.of("lobby"), List.of("lobby"))) {
      lobby.refuseWith = WHITELIST;
      RegisteredServer lobbyView = proxy.runtime.servers().getServer("lobby").orElseThrow();
      proxy.recorder.hook = event -> {
        if (event instanceof PlayerKickedFromServerEvent kicked) kicked.setResult(new KickResult.Redirect(lobbyView, Optional.empty()));
      };
      try (Client joining = Client.join(proxy.port(), "looper")) {
        require(proxy.recorder.await(PlayerServerConnectedEvent.class, 1), "joined");
        require(proxy.runtime.player("looper").orElseThrow().currentServer().name().equals("survival"), "the walk went on to the next server");
      }
      require(lobby.logins.get() == 1, "the lobby was asked once, not " + lobby.logins.get() + " times");

      // The same listener on the fallback path: survival goes away, the lobby refuses.
      lobby.refuseWith = null;
      proxy.recorder.hook = event -> { };
      RegisteredServer survivalView = proxy.runtime.servers().getServer("survival").orElseThrow();
      try (Client client = Client.join(proxy.port(), "faller")) {
        require(proxy.recorder.await(PlayerServerConnectedEvent.class, 2), "joined");
        Player player = proxy.runtime.player("faller").orElseThrow();
        require(player.connectWithResult(survivalView).get(15, TimeUnit.SECONDS).successful(), "on survival");
        int before = lobby.logins.get();
        lobby.refuseWith = WHITELIST;
        proxy.recorder.hook = event -> {
          if (event instanceof PlayerKickedFromServerEvent kicked) kicked.setResult(new KickResult.Redirect(lobbyView, Optional.empty()));
        };
        survival.drop();
        require(client.await(p -> id(p) == DISCONNECT_OUT && text(p).contains("You are not whitelisted")), "disconnected with the lobby's reason");
        require(lobby.logins.get() == before + 1, "the lobby was asked once on the way down, not " + (lobby.logins.get() - before) + " times");
      }
    }
  }

  /** The player was dropped with nothing written: "Connection lost", and no word of why. */
  private static void aLostBackendWithNowhereToGoSaysWhy() throws Exception {
    Backend spare = new Backend("spare");
    spare.close();
    try (Backend lobby = new Backend("lobby");
         Fixture proxy = new Fixture(List.of(lobby, spare), List.of("lobby"), List.of("lobby", "spare"))) {
      try (Client client = Client.join(proxy.port(), "stranded")) {
        require(proxy.recorder.await(PlayerServerConnectedEvent.class, 1), "joined");
        lobby.drop();
        require(client.await(p -> id(p) == DISCONNECT_OUT), "a disconnect screen with a reason, not a dropped socket");
        String reason = text(client.received(p -> id(p) == DISCONNECT_OUT).getFirst());
        require(reason.contains("lobby") && !reason.contains("127.0.0.1") && !reason.contains("Exception"),
            "naming the server and nothing internal, got " + reason);
        require(client.ends(), "and the session ends");
      }
    }
  }

  /** Not a change: the guard on what already worked. One message, bounded, and the slot comes back. */
  private static void everyBackendDownGivesOneMessageAndFreesTheSlot() throws Exception {
    Backend first = new Backend("first");
    Backend second = new Backend("second");
    first.close();
    second.close();
    try (Fixture proxy = new Fixture(List.of(first, second), List.of("first", "second"), List.of("first", "second"))) {
      long started = System.nanoTime();
      try (Client joining = Client.open(proxy.port(), "nobody")) {
        byte[] reply = joining.readDirect();
        require(id(reply) == 0 && text(reply).contains("Could not connect you to a server"), "one clear message, got " + text(reply));
      }
      long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      require(took < 12_000, "bounded by two connect attempts, took " + took + " ms");
      require(waitFor(() -> proxy.proxy.activeConnections() == 0, 5_000), "the connection slot is released");
    }
  }

  /**
   * Health learned of a dead backend only from its own probes, three failures ten seconds apart:
   * until then every joining player waited out a connect to it first.
   */
  private static void connectFailuresCountAgainstHealthWhileChecksRun() throws Exception {
    Backend dead = new Backend("dead");
    Backend gone = new Backend("gone");
    dead.close();
    gone.close();
    try (Backend picky = new Backend("picky"); Backend lobby = new Backend("lobby");
         Fixture proxy = new Fixture(List.of(dead, picky, lobby, gone), List.of("dead", "picky", "lobby"), List.of("lobby"), PASSIVE_ONLY)) {
      picky.refuseWith = WHITELIST;
      BackendHealthService health = proxy.runtime.health();
      try (Client one = Client.join(proxy.port(), "one")) {
        require(failedAt(proxy, "dead") == 1 && health.snapshot("dead").health() != BackendHealth.UNHEALTHY, "one failure is not a verdict");
      }
      try (Client two = Client.join(proxy.port(), "two")) {
        require(failedAt(proxy, "dead") == 2 && health.snapshot("dead").health() == BackendHealth.UNHEALTHY, "the threshold's worth is");
      }
      try (Client three = Client.join(proxy.port(), "three")) {
        require(proxy.recorder.await(PlayerServerConnectedEvent.class, 3), "joined");
        require(failedAt(proxy, "dead") == 2, "the next player is not sent to it");
        require(proxy.runtime.player("three").orElseThrow().currentServer().name().equals("lobby"), "and lands on lobby");
        // A server that answers and refuses the player is up.
        require(picky.logins.get() == 3 && health.snapshot("picky").consecutiveFailures() == 0, "a refusal is not a failure");
        // The switch path counts too.
        Player player = proxy.runtime.player("three").orElseThrow();
        ConnectResult result = player.connectWithResult(proxy.runtime.servers().getServer("gone").orElseThrow()).get(15, TimeUnit.SECONDS);
        require(!result.successful() && health.snapshot("gone").consecutiveFailures() == 1, "a failed switch is counted, got " + health.snapshot("gone"));
      }
    }
    // With checks off nothing would ever count a failure back, so none is counted: every player still tries.
    Backend off = new Backend("off");
    off.close();
    try (Backend lobby = new Backend("lobby");
         Fixture proxy = new Fixture(List.of(off, lobby), List.of("off", "lobby"), List.of("lobby"))) {
      for (String name : List.of("uno", "dos")) try (Client client = Client.join(proxy.port(), name)) { }
      require(failedAt(proxy, "off") == 2 && proxy.runtime.health().snapshot("off").health() == BackendHealth.UNKNOWN,
          "tried by both, and no verdict kept, got " + proxy.runtime.health().snapshot("off"));
    }
  }

  private static long failedAt(Fixture proxy, String server) {
    return proxy.recorder.of(PlayerServerSwitchFailedEvent.class).stream().filter(failed -> failed.target().getName().equals(server)).count();
  }

  /**
   * Health kept its own copy of the configured servers: a plugin's server was never probed, could not
   * be drained, and one unregistered and registered again inherited whatever its namesake had.
   */
  private static void serversRegisteredAtRuntimeAreHealthCheckedAndForgotten() throws Exception {
    Backend dead = new Backend("dead");
    dead.close();
    try (Backend lobby = new Backend("lobby"); Backend live = new Backend("live");
         Fixture proxy = new Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"), new HealthSettings(true, 60_000, 1_500, 1, 1))) {
      BackendHealthService health = proxy.runtime.health();
      proxy.runtime.servers().register("extra", dead.server().address());
      health.probeOnce();
      require(health.snapshot("extra").health() == BackendHealth.UNHEALTHY, "a plugin's server is probed, got " + health.snapshot("extra"));
      require(health.all().stream().anyMatch(snapshot -> snapshot.name().equals("extra")), "and listed");
      require(health.drain("extra") && health.isDraining("extra"), "and can be drained");
      require(proxy.runtime.servers().unregister("extra"), "unregistered");
      proxy.runtime.servers().register("extra", live.server().address());
      require(health.snapshot("extra").health() == BackendHealth.UNKNOWN && !health.isDraining("extra"),
          "a new server under an old name starts afresh, got " + health.snapshot("extra"));
      require(proxy.runtime.selector().isEligible("extra", 47, false), "and is routable");
    }
  }

  /** Every configured name was looked up with orElseThrow, so a server a plugin removed broke joins and /hub. */
  private static void aConfiguredServerUnregisteredAtRuntimeLeavesJoinsWorking() throws Exception {
    try (Backend lobby = new Backend("lobby"); Backend survival = new Backend("survival");
         Fixture proxy = new Fixture(List.of(lobby, survival), List.of("lobby"), List.of("lobby", "survival"))) {
      require(proxy.runtime.servers().unregister("lobby"), "a plugin removes the lobby");
      for (int i = 0; i < 3; i++) proxy.runtime.health().applyProbeResult("survival", false);
      // Nothing is eligible, so the last resort tries every configured server that still exists.
      try (Client client = Client.join(proxy.port(), "survivor")) {
        require(proxy.recorder.await(PlayerServerConnectedEvent.class, 1), "joined");
        require(proxy.runtime.player("survivor").orElseThrow().currentServer().name().equals("survival"), "on survival");
        client.send(chat("/hub"));
        require(client.await(p -> id(p) == CHAT_OUT && text(p).contains("already connected")), "/hub answers");
      }
    }
  }

  /** Turning checks off kept their last verdicts, and a server marked unhealthy stayed out of routing for good. */
  private static void disablingHealthChecksForgetsTheirVerdicts() throws Exception {
    BackendServer lobby = new BackendServer("lobby", new java.net.InetSocketAddress("127.0.0.1", NativeApiTests.reservePort()));
    var configuration = VelocityCompatTests.configuration(List.of(lobby));
    try (BackendHealthService health = new BackendHealthService(new ServerRegistry(configuration), PASSIVE_ONLY)) {
      BackendSelector selector = new BackendSelector(configuration, health);
      health.connectFailed("lobby");
      health.connectFailed("lobby");
      require(!selector.isEligible("lobby", 47, false), "unhealthy is not routable");
      health.applySettings(new HealthSettings(false, 60_000, 1_500, 2, 2));
      require(health.snapshot("lobby").health() == BackendHealth.UNKNOWN && selector.isEligible("lobby", 47, false),
          "with checks off it is routable again, got " + health.snapshot("lobby"));
      health.connectFailed("lobby");
      health.connectFailed("lobby");
      require(health.snapshot("lobby").consecutiveFailures() == 0, "and failures are not counted while off");
    }
  }

  /** A plugin's switch to a server checks had marked down dialled it anyway and waited out the connect. */
  private static void aSwitchToAServerKnownDownFailsAtOnce() throws Exception {
    try (Backend lobby = new Backend("lobby"); Backend survival = new Backend("survival");
         Fixture proxy = new Fixture(List.of(lobby, survival), List.of("lobby"), List.of("lobby"))) {
      RegisteredServer survivalView = proxy.runtime.servers().getServer("survival").orElseThrow();
      try (Client client = Client.join(proxy.port(), "hopper")) {
        require(proxy.recorder.await(PlayerServerConnectedEvent.class, 1), "joined");
        Player player = proxy.runtime.player("hopper").orElseThrow();
        for (int i = 0; i < 3; i++) proxy.runtime.health().applyProbeResult("survival", false);
        long started = System.nanoTime();
        ConnectResult refused = player.connectWithResult(survivalView).get(5, TimeUnit.SECONDS);
        long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        require(refused.status() == ConnectResult.Status.FAILED && refused.reason().contains("unavailable") && took < 1_000,
            "failed at once with a reason, got " + refused + " in " + took + " ms");
        require(survival.logins.get() == 0, "survival was never dialled");
        require(!((TrackedPlayer) player).transferTo("survival") && client.await(p -> id(p) == CHAT_OUT && text(p).contains("is unavailable. Please try again later")),
            "a player-facing switch says so");
        for (int i = 0; i < 2; i++) proxy.runtime.health().applyProbeResult("survival", true);
        require(player.connectWithResult(survivalView).get(15, TimeUnit.SECONDS).successful(), "once healthy it is used again");
      }
    }
  }

  /** /hub took the first configured server whatever health said, and a player waited on a dead lobby. */
  private static void hubPrefersALobbyHealthChecksAllow() throws Exception {
    try (Backend first = new Backend("first"); Backend second = new Backend("second"); Backend survival = new Backend("survival");
         Fixture proxy = new Fixture(List.of(first, second, survival), List.of("first", "second"), List.of("first"))) {
      try (Client client = Client.join(proxy.port(), "homing")) {
        require(proxy.recorder.await(PlayerServerConnectedEvent.class, 1), "joined");
        Player player = proxy.runtime.player("homing").orElseThrow();
        require(player.connectWithResult(proxy.runtime.servers().getServer("survival").orElseThrow()).get(15, TimeUnit.SECONDS).successful(), "on survival");
        for (int i = 0; i < 3; i++) proxy.runtime.health().applyProbeResult("first", false);
        client.send(chat("/hub"));
        require(waitFor(() -> player.currentServer().name().equals("second"), 10_000), "sent to the lobby that is up, on " + player.currentServer().name());
        require(first.logins.get() == 1, "the one marked down was not dialled again");
      }
    }
  }
}
