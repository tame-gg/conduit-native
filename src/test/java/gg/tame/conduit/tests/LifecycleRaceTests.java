// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.NativeApiTests.require;
import static gg.tame.conduit.tests.NativeApiTests.waitFor;

import gg.tame.conduit.api.event.player.PlayerDisconnectEvent;
import gg.tame.conduit.api.event.player.PlayerPostLoginEvent;
import gg.tame.conduit.api.event.player.PlayerServerConnectedEvent;
import gg.tame.conduit.api.player.ConnectResult;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.api.server.RegisteredServer;
import gg.tame.conduit.config.AuthenticationSettings;
import gg.tame.conduit.tests.NativeApiTests.Backend;
import gg.tame.conduit.tests.NativeApiTests.Client;
import gg.tame.conduit.tests.NativeApiTests.Fixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A player's life crossing the proxy's and the plugins' at the worst moments: a client that leaves
 * while its switch is under way, a proxy stopped mid-switch, a plugin disabled while its listener
 * holds a login, and plugin code acting on a player who is leaving. Each must end with the switch
 * answered, the player told of once, and no slot, socket or thread held.
 */
public final class LifecycleRaceTests {
  public static void main(String[] arguments) throws Exception { run(); }

  public static void run() throws Exception {
    aClientLeavingMidSwitchEndsBoth();
    stoppingTheProxyMidSwitchReturnsPromptly();
    aPluginDisabledWhileItsListenerHoldsALoginLetsItFinish();
    connectingALeavingPlayerFailsInsteadOfHanging();
    System.out.println("LifecycleRaceTests OK");
  }

  /** The switch to a silent backend is waiting on it when the client hangs up. */
  private static void aClientLeavingMidSwitchEndsBoth() throws Exception {
    try (Backend lobby = new Backend("lobby"); Backend slow = new Backend("slow");
         Fixture proxy = new Fixture(List.of(lobby, slow), List.of("lobby"), List.of("lobby"))) {
      slow.silent = true;
      Client client = Client.join(proxy.port(), "leaver");
      require(proxy.recorder.await(PlayerServerConnectedEvent.class, 1), "joined");
      Player player = proxy.runtime.player("leaver").orElseThrow();
      CompletableFuture<ConnectResult> switching = player.connectWithResult(proxy.runtime.servers().getServer("slow").orElseThrow());
      require(waitFor(() -> slow.logins.get() == 1, 5_000), "the switch is under way");
      client.close();
      ConnectResult result = switching.get(15, TimeUnit.SECONDS);
      require(!result.successful(), "a switch whose player left does not succeed, got " + result);
      require(waitFor(() -> proxy.runtime.player("leaver").isEmpty(), 10_000), "the player is gone");
      require(waitFor(() -> proxy.recorder.of(PlayerDisconnectEvent.class).size() == 1, 5_000)
          && proxy.recorder.of(PlayerDisconnectEvent.class).size() == 1, "and heard of once, got " + proxy.recorder.of(PlayerDisconnectEvent.class));
      require(waitFor(() -> proxy.proxy.activeConnections() == 0, 10_000), "the connection slot is released");
    }
  }

  /** Closing the proxy while a switch waits on a silent backend must not wait out the switch. */
  private static void stoppingTheProxyMidSwitchReturnsPromptly() throws Exception {
    try (Backend lobby = new Backend("lobby"); Backend slow = new Backend("slow")) {
      slow.silent = true;
      Fixture proxy = new Fixture(List.of(lobby, slow), List.of("lobby"), List.of("lobby"));
      CompletableFuture<ConnectResult> switching;
      try (Client client = Client.join(proxy.port(), "stranded")) {
        require(proxy.recorder.await(PlayerServerConnectedEvent.class, 1), "joined");
        Player player = proxy.runtime.player("stranded").orElseThrow();
        switching = player.connectWithResult(proxy.runtime.servers().getServer("slow").orElseThrow());
        require(waitFor(() -> slow.logins.get() == 1, 5_000), "the switch is under way");
        long started = System.nanoTime();
        proxy.close();
        long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        require(took < 10_000, "the proxy closed in " + took + " ms");
        require(client.ends(), "the client was let go");
      }
      require(!switching.get(15, TimeUnit.SECONDS).successful(), "the switch is answered, and not as a success");
      require(proxy.recorder.of(PlayerDisconnectEvent.class).size() == 1, "the player was heard of once");
    }
  }

  public static final CountDownLatch HOLD = new CountDownLatch(1);
  public static final CountDownLatch HELD = new CountDownLatch(1);

  /**
   * A plugin whose login listener is still running when the plugin is disabled: the login it holds
   * goes on once the listener returns, and the next login does not reach the disabled plugin at all.
   */
  private static void aPluginDisabledWhileItsListenerHoldsALoginLetsItFinish() throws Exception {
    Path root = TempFiles.dir("conduit-race-holder");
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    CommandApiTests.buildPluginJar(plugins.resolve("holder.jar"), "holder", "holder.HolderPlugin", 1, """
        package holder;
        import gg.tame.conduit.api.event.Subscribe;
        import gg.tame.conduit.api.event.player.PlayerLoginEvent;
        import gg.tame.conduit.tests.LifecycleRaceTests;
        public final class HolderPlugin extends gg.tame.conduit.api.plugin.ConduitPlugin {
          @Override public void onEnable() { proxy().events().register(this, this); }
          @Subscribe public void login(PlayerLoginEvent event) {
            LifecycleRaceTests.HELD.countDown();
            try { LifecycleRaceTests.HOLD.await(10, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException stop) { }
          }
        }
        """);
    try (Backend lobby = new Backend("lobby");
         Fixture proxy = new Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"), plugins, AuthenticationSettings.offline(), null)) {
      require(waitFor(() -> proxy.runtime.plugins().plugin("holder").isPresent(), 10_000), "the plugin is enabled");
      CompletableFuture<Client> held = CompletableFuture.supplyAsync(() -> {
        try { return Client.join(proxy.port(), "held"); } catch (java.io.IOException failed) { throw new java.io.UncheckedIOException(failed); }
      });
      require(HELD.await(10, TimeUnit.SECONDS), "the plugin's listener holds the login");
      proxy.runtime.plugins().disable(proxy.runtime.plugins().plugin("holder").orElseThrow());
      HOLD.countDown();
      try (Client first = held.get(15, TimeUnit.SECONDS)) {
        require(proxy.recorder.await(PlayerPostLoginEvent.class, 1), "the held login finished");
        long started = System.nanoTime();
        try (Client second = Client.join(proxy.port(), "next")) {
          require(proxy.recorder.await(PlayerPostLoginEvent.class, 2), "the next login goes through");
          require(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5_000, "without the disabled plugin's listener holding it");
        }
      }
    }
  }

  /**
   * A plugin moving a player from its PlayerDisconnectEvent listener, as a lobby plugin tidying up
   * might: the player is on their way out, so the move fails at once instead of waiting on a session
   * that is closing.
   */
  private static void connectingALeavingPlayerFailsInsteadOfHanging() throws Exception {
    try (Backend lobby = new Backend("lobby"); Backend other = new Backend("other");
         Fixture proxy = new Fixture(List.of(lobby, other), List.of("lobby"), List.of("lobby"))) {
      RegisteredServer target = proxy.runtime.servers().getServer("other").orElseThrow();
      CompletableFuture<ConnectResult> attempted = new CompletableFuture<>();
      proxy.recorder.hook = event -> {
        if (event instanceof PlayerDisconnectEvent leaving) leaving.player().connectWithResult(target).whenComplete((result, failed) -> {
          if (failed != null) attempted.completeExceptionally(failed); else attempted.complete(result);
        });
      };
      try (Client client = Client.join(proxy.port(), "tidy")) {
        require(proxy.recorder.await(PlayerServerConnectedEvent.class, 1), "joined");
      }
      ConnectResult result = attempted.get(10, TimeUnit.SECONDS);
      require(!result.successful(), "a leaving player cannot be moved, got " + result);
      require(other.logins.get() == 0, "and no backend is dialled for them");
      require(waitFor(() -> proxy.proxy.activeConnections() == 0, 10_000), "the slot is released");
    }
  }
}
