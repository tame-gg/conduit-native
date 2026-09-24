// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.api.ConduitProxy;
import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.event.Subscribe;
import gg.tame.conduit.api.event.command.CommandExecuteEvent;
import gg.tame.conduit.api.event.messaging.PluginMessageEvent;
import gg.tame.conduit.api.event.player.PlayerAuthenticatedEvent;
import gg.tame.conduit.api.event.player.PlayerChatEvent;
import gg.tame.conduit.api.event.player.PlayerDisconnectEvent;
import gg.tame.conduit.api.event.player.PlayerInitialServerEvent;
import gg.tame.conduit.api.event.player.PlayerKickedFromServerEvent;
import gg.tame.conduit.api.event.player.PlayerKickedFromServerEvent.KickResult;
import gg.tame.conduit.api.event.player.PlayerLoginEvent;
import gg.tame.conduit.api.event.player.PlayerPostLoginEvent;
import gg.tame.conduit.api.event.player.PlayerServerConnectEvent;
import gg.tame.conduit.api.event.player.PlayerServerConnectedEvent;
import gg.tame.conduit.api.event.player.PlayerServerSwitchEvent;
import gg.tame.conduit.api.event.player.PlayerServerSwitchFailedEvent;
import gg.tame.conduit.api.event.player.PlayerSetupEvent;
import gg.tame.conduit.api.event.proxy.ServerListPingEvent;
import gg.tame.conduit.api.player.ConnectResult;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.api.plugin.Plugin;
import gg.tame.conduit.api.plugin.PluginDescription;
import gg.tame.conduit.api.scheduler.ScheduledTask;
import gg.tame.conduit.api.scheduler.Scheduler;
import gg.tame.conduit.api.server.RegisteredServer;
import gg.tame.conduit.api.server.ServerStatus;
import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.api.text.TextColor;
import gg.tame.conduit.auth.PlayerAuthenticator;
import gg.tame.conduit.config.AuthenticationMode;
import gg.tame.conduit.config.AuthenticationSettings;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.config.ForwardingMode;
import gg.tame.conduit.config.HealthSettings;
import gg.tame.conduit.config.OpsSettings;
import gg.tame.conduit.config.StatusSettings;
import gg.tame.conduit.event.ConduitEventManager;
import gg.tame.conduit.login.PlayerProfile;
import gg.tame.conduit.network.MinecraftProxy;
import gg.tame.conduit.network.PacketTransport;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.PluginMessage;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.runtime.ConduitRuntime;
import gg.tame.conduit.scheduler.ConduitScheduler;
import gg.tame.conduit.text.TextCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * The native plugin API against a real proxy: scripted 1.8.9 clients and backends on loopback, so
 * every event is shown to be fired by the core and every cancellation to change what goes on the wire.
 */
public final class NativeApiTests {
  public static void main(String[] a) throws Exception { run(); }

  public static void run() throws Exception {
    listenersRunInOrderAndSurviveAThrowingOne();
    aRejectedListenerRegistersNothing();
    throwingTasksKeepRepeatingAndFinishedOnesAreForgotten();
    aDisabledPluginCannotReschedule();
    aBlockingPluginTaskHoldsUpNoOtherPlugin();
    statusSettingsLoadFromConfig();
    theMotdTakesMiniMessage();
    loginCanBeDeniedAndJoinEventsFireInOrder();
    onlineLoginFiresAuthenticated();
    initialServerCanBeChosenCancelledOrRedirected();
    switchesReportResultsAndHonourCancellation();
    aLostBackendFallsBackThroughTheSameEvents();
    pluginMessagesCanBeDroppedOrSent();
    chatAndCommandEventsCanBeCancelled();
    disconnectShowsTheKickScreen();
    aThrowingPermissionProviderDenies();
    proxyLifecycleReachesPluginsInOrder();
    theServerListShowsTheConfiguredEntry();
    pingListenersCanRewriteCancelAndSurviveAThrowingOne();
    aLiveBackendPingAsksTheBackend();
    aKickWhilePlayingReachesTheClientAsTheBackendWroteIt();
    aKickListenerCanRedirectThePlayer();
    aShuttingDownBackendMovesThePlayerRatherThanDisconnectingThem();
    aRefusedSwitchKeepsThePlayerAndTellsThem();
    aRefusedFirstServerMovesOnAndExplains();
    theClientsLanguageIsKnownOnceItSendsItsSettings();
    System.out.println("NativeApiTests OK");
  }

  // --- event bus and scheduler, no sockets ---------------------------------------------------

  private static void listenersRunInOrderAndSurviveAThrowingOne() {
    ConduitEventManager events = new ConduitEventManager();
    List<String> ran = Collections.synchronizedList(new ArrayList<>());
    events.register(new TestPlugin("late"), new Object() {
      @Subscribe(order = Subscribe.Order.LAST) public void last(Event event) { ran.add("last:" + ((Probe) event).cancelled); }
    });
    events.register(new TestPlugin("broken"), new Object() {
      @Subscribe public void boom(Probe event) { ran.add("boom"); throw new IllegalStateException("listener bug"); }
    });
    events.register(new TestPlugin("early"), new Object() {
      @Subscribe(order = Subscribe.Order.FIRST) public void first(Probe event) { ran.add("first"); event.cancelled = true; }
      @Subscribe public void normal(Probe event) { ran.add("normal"); }
    });
    Probe fired = events.fire(new Probe());
    require(ran.equals(List.of("first", "boom", "normal", "last:true")), "FIRST, then NORMAL in registration order, then LAST, got " + ran);
    require(fired.cancelled, "the event kept what the listeners gave it");
  }

  /** A listener with one good method and one bad used to leave the good one registered after the throw. */
  private static void aRejectedListenerRegistersNothing() {
    ConduitEventManager events = new ConduitEventManager();
    List<String> ran = new ArrayList<>();
    boolean threw = false;
    try {
      events.register(new TestPlugin("half"), new Object() {
        @Subscribe public void good(Probe event) { ran.add("good"); }
        @Subscribe public void bad(Probe event, String extra) { }
      });
    } catch (IllegalArgumentException expected) { threw = true; }
    require(threw, "refused");
    events.fire(new Probe());
    require(ran.isEmpty(), "no half of a refused listener stays registered");
  }

  /**
   * An Error from a repeating task used to end the repetition silently, and every finished one-shot
   * task stayed in the scheduler's table for the life of the process.
   */
  private static void throwingTasksKeepRepeatingAndFinishedOnesAreForgotten() throws Exception {
    try (ConduitScheduler scheduler = new ConduitScheduler()) {
      Plugin plugin = new TestPlugin("tasks");
      AtomicInteger runs = new AtomicInteger();
      ScheduledTask repeating = scheduler.buildTask(plugin, () -> {
        runs.incrementAndGet();
        throw new AssertionError("task bug");
      }).repeat(Duration.ofMillis(5)).schedule();
      require(waitFor(() -> runs.get() >= 3, 5_000), "a task that throws an Error still repeats, ran " + runs.get());
      repeating.cancel();
      CountDownLatch done = new CountDownLatch(20);
      for (int i = 0; i < 20; i++) scheduler.buildTask(plugin, done::countDown).schedule();
      require(done.await(5, TimeUnit.SECONDS), "one-shot tasks ran");
      require(waitFor(() -> scheduler.taskCount() == 0, 5_000), "finished and cancelled tasks are forgotten, left " + scheduler.taskCount());
      scheduler.cancel(plugin);
      require(!scheduler.buildTask(plugin, () -> { }).delay(Duration.ofHours(1)).schedule().cancelled(),
          "cancel(plugin) alone does not stop a plugin scheduling again");
    }
  }

  /** A task running while its plugin was disabled scheduled its successor after the sweep, forever. */
  private static void aDisabledPluginCannotReschedule() throws Exception {
    try (ConduitScheduler scheduler = new ConduitScheduler()) {
      Plugin plugin = new TestPlugin("chain");
      CountDownLatch running = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      AtomicInteger successors = new AtomicInteger();
      List<Throwable> refusals = Collections.synchronizedList(new ArrayList<>());
      scheduler.buildTask(plugin, () -> {
        running.countDown();
        try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { return; }
        try { scheduler.buildTask(plugin, successors::incrementAndGet).repeat(Duration.ofMillis(5)).schedule(); }
        catch (IllegalStateException refused) { refusals.add(refused); }
      }).schedule();
      require(running.await(5, TimeUnit.SECONDS), "task started");
      scheduler.retire(plugin);
      release.countDown();
      require(waitFor(() -> !refusals.isEmpty(), 5_000), "the running task's successor was refused");
      Thread.sleep(100);
      require(successors.get() == 0 && scheduler.taskCount() == 0, "nothing of the disabled plugin runs on");
    }
  }

  /**
   * Every plugin's tasks shared two threads, so one plugin's task blocking on a slow socket held
   * back every other plugin's timers for as long as it blocked.
   */
  private static void aBlockingPluginTaskHoldsUpNoOtherPlugin() throws Exception {
    try (ConduitScheduler scheduler = new ConduitScheduler()) {
      Plugin stuck = new TestPlugin("stuck");
      Plugin lively = new TestPlugin("lively");
      CountDownLatch release = new CountDownLatch(1);
      CountDownLatch blocking = new CountDownLatch(3);
      for (int i = 0; i < 3; i++) {
        scheduler.buildTask(stuck, () -> {
          blocking.countDown();
          try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }).schedule();
      }
      require(blocking.await(5, TimeUnit.SECONDS), "the blocking tasks all started, on threads of their own");
      List<String> ran = Collections.synchronizedList(new ArrayList<>());
      CountDownLatch other = new CountDownLatch(1);
      scheduler.buildTask(lively, () -> { ran.add(Thread.currentThread().getName()); other.countDown(); }).schedule();
      require(other.await(2, TimeUnit.SECONDS), "another plugin's task runs while the first plugin's block");
      require(ran.getFirst().startsWith("conduit-plugin-lively-"), "on its own plugin's thread, got " + ran);
      require(threads("conduit-plugin-stuck-") == 3, "the stuck plugin holds its own threads, got " + threads("conduit-plugin-stuck-"));

      AtomicInteger inside = new AtomicInteger();
      AtomicInteger overlapped = new AtomicInteger();
      AtomicInteger runs = new AtomicInteger();
      ScheduledTask slow = scheduler.buildTask(lively, () -> {
        if (inside.incrementAndGet() > 1) overlapped.incrementAndGet();
        try { Thread.sleep(30); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        inside.decrementAndGet();
        runs.incrementAndGet();
      }).repeat(Duration.ofMillis(2)).schedule();
      require(waitFor(() -> runs.get() >= 5, 5_000), "a slow repeating task keeps repeating");
      slow.cancel();
      require(overlapped.get() == 0, "and never overlaps itself");

      scheduler.retire(stuck);
      require(threads("conduit-plugin-stuck-") == 3, "a retired plugin's running tasks are not interrupted");
      release.countDown();
      require(waitFor(() -> threads("conduit-plugin-stuck-") == 0, 5_000), "and its threads end once they return");
    }
  }

  private static long threads(String prefix) {
    return Thread.getAllStackTraces().keySet().stream().filter(thread -> thread.getName().startsWith(prefix)).count();
  }

  /**
   * The MOTD is read as Velocity reads its own, which is MiniMessage -- {@code <red>Conduit</red>}
   * used to reach the server list as those nineteen characters, tags and all.
   *
   * <p>The three forms have to coexist, because every existing conduit.toml is written in one of the
   * other two: a MiniMessage MOTD is parsed, an {@code &}-code MOTD still is, and plain text with a
   * stray {@code <} in it is neither and stays exactly as typed.
   */
  private static void theMotdTakesMiniMessage() {
    require(gg.tame.conduit.text.MiniMessages.available(), "MiniMessage is on the test class path");

    Text tagged = StatusSettings.parseMotd("<red>Conduit</red>");
    require(tagged.plain().equals("Conduit"), "the tags are consumed, got \"" + tagged.plain() + "\"");
    require(TextCodec.toJson(tagged, 765).contains("\"color\":\"red\""), "and become the colour, got " + TextCodec.toJson(tagged, 765));

    Text gradient = StatusSettings.parseMotd("<bold><gradient:#5e4fa2:#f79459>Hi</gradient></bold>");
    String json = TextCodec.toJson(gradient, 765);
    require(gradient.plain().equals("Hi") && json.contains("#5e4fa2") && json.contains("#f79459") && json.contains("\"bold\":true"),
        "a gradient becomes per-character colours, got " + json);

    require(StatusSettings.parseMotd("&cHi").equals(Text.of("Hi").color(TextColor.RED)), "& codes still work");
    require(StatusSettings.parseMotd("Conduit").equals(Text.of("Conduit")), "plain text stays plain");
    // Not a tag, and a reader that guessed from the '<' alone would have swallowed it.
    require(StatusSettings.parseMotd("Welcome <3").equals(Text.of("Welcome <3")), "an unclosed '<' is left alone");
    // MiniMessage throws on this; a MOTD keeps its listing and loses only its colours.
    require(StatusSettings.parseMotd("<gradient:notacolour>x</gradient>").plain().equals("<gradient:notacolour>x</gradient>"),
        "a malformed tag falls back to the text as written");
  }

  private static void statusSettingsLoadFromConfig() throws Exception {
    Text motd = StatusSettings.parseMotd("&aGreen &lbold&r plain\\nline & two&k!");
    require(TextCodec.toJson(motd, 765).equals("{\"text\":\"\",\"extra\":[{\"text\":\"Green \",\"color\":\"green\"},"
        + "{\"text\":\"bold\",\"color\":\"green\",\"bold\":true},{\"text\":\" plain\\nline & two\"},{\"text\":\"!\",\"obfuscated\":true}]}"),
        "& codes become components and \\n a line break, got " + TextCodec.toJson(motd, 765));
    require(StatusSettings.parseMotd("Conduit").equals(Text.of("Conduit")), "a MOTD with no codes is plain text");

    Path dir = TempFiles.dir("conduit-status-config");
    Files.write(dir.resolve("icon.png"), png(64, 64));
    Files.write(dir.resolve("big.png"), png(128, 128));
    Files.write(dir.resolve("icon.txt"), "not a png".getBytes(StandardCharsets.UTF_8));
    require(StatusSettings.favicon(dir.resolve("big.png")).isEmpty(), "a favicon that is not 64x64 is refused");
    require(StatusSettings.favicon(dir.resolve("icon.txt")).isEmpty(), "one that is not a PNG is refused");
    require(StatusSettings.favicon(dir.resolve("missing.png")).isEmpty(), "one that cannot be read is refused, not fatal");
    Path config = dir.resolve("conduit.toml");
    String base = "[listener]\nhost = \"127.0.0.1\"\nport = 25565\nmax-frame-bytes = 1048576\n[forwarding]\nmode = \"none\"\n"
        + "[servers.lobby]\naddress = \"127.0.0.1:25566\"\n[routing]\ninitial = [\"lobby\"]\nfallback = [\"lobby\"]\n";
    Files.writeString(config, base);
    var defaults = gg.tame.conduit.config.ConfigurationLoader.load(config).status();
    require(defaults.motd().equals(Text.of("Conduit")) && defaults.displayMaxPlayers() == 100 && defaults.favicon().isEmpty(),
        "no [status] section keeps the old MOTD, got " + defaults);
    Files.writeString(config, base + "[status]\nmotd = \"&cHi\"\ndisplay-max-players = 250\nfavicon = \"icon.png\"\n");
    var configured = gg.tame.conduit.config.ConfigurationLoader.load(config).status();
    require(configured.motd().equals(Text.of("Hi").color(TextColor.RED)) && configured.displayMaxPlayers() == 250, "configured, got " + configured);
    require(configured.favicon().orElseThrow().equals("data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png(64, 64))),
        "the favicon, relative to the config file, as a data URI");
    Files.writeString(config, base + "[status]\nfavicon = \"big.png\"\n");
    require(gg.tame.conduit.config.ConfigurationLoader.load(config).status().favicon().isEmpty(), "a bad favicon does not stop the load");
  }

  /** Just the header a PNG reader looks at for the size; the body is never decoded. */
  private static byte[] png(int width, int height) {
    return java.nio.ByteBuffer.allocate(33).putLong(0x89504E470D0A1A0AL).putInt(13).putInt(0x49484452)
        .putInt(width).putInt(height).put(new byte[] {8, 6, 0, 0, 0}).putInt(0).array();
  }

  // --- sessions -----------------------------------------------------------------------------

  private static void loginCanBeDeniedAndJoinEventsFireInOrder() throws Exception {
    try (Backend lobby = new Backend("lobby"); Fixture proxy = new Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"))) {
      proxy.recorder.hook = event -> {
        if (event instanceof PlayerLoginEvent login && login.player().username().equals("denied")) login.deny(Text.of("Go away").color(TextColor.RED));
      };
      try (Client denied = Client.open(proxy.port(), "denied")) {
        byte[] reply = denied.readDirect();
        require(PlayPackets.packetId(reply) == P47.id(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_DISCONNECT),
            "a denied login gets a login disconnect, got id " + PlayPackets.packetId(reply));
        require(text(reply).contains("Go away") && text(reply).contains("red"), "with the formatted reason, got " + text(reply));
        require(denied.ends(), "and the connection ends");
      }
      require(lobby.logins.get() == 0, "a denied login dials no backend");
      proxy.recorder.hook = event -> { };

      try (Client kyle = Client.join(proxy.port(), "kyle")) {
        require(proxy.recorder.await(PlayerServerConnectedEvent.class, 1), "joined");
        require(proxy.recorder.names(PlayerSetupEvent.class, PlayerLoginEvent.class, PlayerInitialServerEvent.class, PlayerServerConnectEvent.class,
                PlayerPostLoginEvent.class, PlayerServerConnectedEvent.class, PlayerDisconnectEvent.class)
            .equals(List.of("PlayerSetupEvent", "PlayerLoginEvent", "PlayerDisconnectEvent",
                "PlayerSetupEvent", "PlayerLoginEvent", "PlayerInitialServerEvent", "PlayerServerConnectEvent",
                "PlayerPostLoginEvent", "PlayerServerConnectedEvent")),
            "join events in order, the denied login's ending included, got " + proxy.recorder.names());
        require(proxy.recorder.of(PlayerDisconnectEvent.class).getFirst().loginStatus() == PlayerDisconnectEvent.LoginStatus.CANCELLED_BY_PROXY,
            "a denied login ends as cancelled by the proxy");
        PlayerServerConnectedEvent connected = proxy.recorder.of(PlayerServerConnectedEvent.class).getFirst();
        require(connected.source().isEmpty() && connected.target().getName().equals("lobby"), "first server has no source");
        Player player = proxy.runtime.player("kyle").orElseThrow(() -> new AssertionError("player lookup after PostLogin"));
        require(proxy.recorder.of(PlayerPostLoginEvent.class).getFirst().player() == player, "PostLogin carries the looked-up player");
        require(player.protocolVersion() == 47, "protocol version");
        require(player.virtualHost().getHostString().equals("localhost") && player.virtualHost().getPort() == 25565, "virtual host from the handshake");
        require(player.remoteAddress().isLoopbackAddress(), "remote address");
        require(player.currentServer().name().equals("lobby"), "current server");
        require(proxy.runtime.servers().getServer("lobby").orElseThrow().players().contains(player), "server lists its players");
        require(proxy.runtime.boundAddress().getPort() == proxy.port(), "bound address is the real one");
        require(!proxy.runtime.onlineMode() && !player.authenticated(), "offline mode");
        require(proxy.recorder.of(PlayerAuthenticatedEvent.class).isEmpty(), "no authenticated event offline");
      }
      require(proxy.recorder.await(PlayerDisconnectEvent.class, 2), "disconnect event");
      require(proxy.recorder.of(PlayerDisconnectEvent.class).size() == 2, "one disconnect event each, the denied login's included");
      require(proxy.recorder.of(PlayerDisconnectEvent.class).getLast().completedLogin(), "the one who joined completed the login");
      require(proxy.runtime.player("kyle").isEmpty(), "gone from the lookup");
    }
  }

  private static void onlineLoginFiresAuthenticated() throws Exception {
    PlayerAuthenticator authenticator = new PlayerAuthenticator() {
      @Override public AuthenticationMode mode() { return AuthenticationMode.ONLINE; }
      @Override public PlayerProfile verify(gg.tame.conduit.auth.SessionQuery query) {
        return new PlayerProfile(new UUID(7, 7), query.username(), List.of(), true);
      }
    };
    AuthenticationSettings online = new AuthenticationSettings(AuthenticationMode.ONLINE, "http://127.0.0.1:1/unused", 1000);
    try (Backend lobby = new Backend("lobby");
         Fixture proxy = new Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"), online, authenticator)) {
      try (Socket socket = new Socket("127.0.0.1", proxy.port())) {
        socket.setSoTimeout(10_000);
        MinecraftFrames.write(socket.getOutputStream(), new Handshake(47, "localhost", 25565, 2).encode());
        MinecraftFrames.write(socket.getOutputStream(), loginStart("notch"));
        var request = gg.tame.conduit.login.EncryptionRequest.decode(P47, MinecraftFrames.read(socket.getInputStream(), 4096));
        byte[] shared = new byte[16];
        new java.security.SecureRandom().nextBytes(shared);
        var key = java.security.KeyFactory.getInstance("RSA").generatePublic(new java.security.spec.X509EncodedKeySpec(request.publicKey()));
        MinecraftFrames.write(socket.getOutputStream(), packet(1, output -> {
          MinecraftOutput.bytes(output, gg.tame.conduit.crypto.RsaKeys.encrypt(key, shared));
          MinecraftOutput.bytes(output, gg.tame.conduit.crypto.RsaKeys.encrypt(key, request.verifyToken()));
        }));
        PacketTransport encrypted = new PacketTransport(
            gg.tame.conduit.crypto.CipherStreams.decrypting(socket.getInputStream(), gg.tame.conduit.crypto.AesCfb8.decryptor(shared)),
            gg.tame.conduit.crypto.CipherStreams.encrypting(socket.getOutputStream(), gg.tame.conduit.crypto.AesCfb8.encryptor(shared)));
        require(PlayPackets.packetId(encrypted.read(4096)) == 2, "encrypted Login Success");
        require(proxy.recorder.await(PlayerPostLoginEvent.class, 1), "joined");
        require(proxy.recorder.names(PlayerLoginEvent.class, PlayerAuthenticatedEvent.class)
            .equals(List.of("PlayerLoginEvent", "PlayerAuthenticatedEvent")), "authenticated right after login, got " + proxy.recorder.names());
        Player player = proxy.recorder.of(PlayerAuthenticatedEvent.class).getFirst().player();
        require(player.authenticated() && player.uniqueId().equals(new UUID(7, 7)), "the authenticated identity");
        require(proxy.runtime.onlineMode(), "online mode reported");
      }
    }
  }

  private static void initialServerCanBeChosenCancelledOrRedirected() throws Exception {
    try (Backend lobby = new Backend("lobby"); Backend survival = new Backend("survival"); Backend hub = new Backend("hub");
         Fixture proxy = new Fixture(List.of(lobby, survival, hub), List.of("lobby", "survival"), List.of("lobby"))) {
      RegisteredServer hubView = proxy.runtime.servers().getServer("hub").orElseThrow();
      List<String> offered = Collections.synchronizedList(new ArrayList<>());
      proxy.recorder.hook = event -> {
        if (event instanceof PlayerInitialServerEvent choose) {
          offered.add(choose.initialServer().map(RegisteredServer::getName).orElse("none"));
          choose.setInitialServer(hubView);
        }
      };
      try (Client chosen = Client.join(proxy.port(), "chosen")) {
        require(proxy.recorder.await(PlayerServerConnectedEvent.class, 1), "joined");
        require(offered.equals(List.of("lobby")), "routing's first choice is offered, got " + offered);
        require(hub.logins.get() == 1 && lobby.logins.get() == 0, "the chosen server, not routing's first");
      }

      proxy.recorder.hook = event -> {
        if (event instanceof PlayerServerConnectEvent connect && connect.target().getName().equals("lobby")) connect.setCancelled(true);
      };
      try (Client skipped = Client.join(proxy.port(), "skipped")) {
        require(proxy.recorder.await(PlayerServerConnectedEvent.class, 2), "joined");
        require(lobby.logins.get() == 0, "a cancelled first connection opens no backend connection");
        require(survival.logins.get() == 1, "the next candidate is tried instead");
      }

      proxy.recorder.hook = event -> {
        if (event instanceof PlayerServerConnectEvent connect && connect.target().getName().equals("lobby")) connect.setTarget(hubView);
      };
      try (Client redirected = Client.join(proxy.port(), "redirected")) {
        require(proxy.recorder.await(PlayerServerConnectedEvent.class, 3), "joined");
        require(lobby.logins.get() == 0 && hub.logins.get() == 2, "redirected before any socket to the original");
        require(proxy.recorder.of(PlayerServerConnectedEvent.class).getLast().target().getName().equals("hub"), "connected event names where it went");
      }
    }
  }

  private static void switchesReportResultsAndHonourCancellation() throws Exception {
    try (Backend lobby = new Backend("lobby"); Backend survival = new Backend("survival"); Backend dead = new Backend("dead");
         Backend slow = new Backend("slow");
         Fixture proxy = new Fixture(List.of(lobby, survival, dead, slow), List.of("lobby"), List.of("lobby"))) {
      dead.refuse = true;
      slow.silent = true;
      RegisteredServer survivalView = proxy.runtime.servers().getServer("survival").orElseThrow();
      RegisteredServer deadView = proxy.runtime.servers().getServer("dead").orElseThrow();
      RegisteredServer lobbyView = proxy.runtime.servers().getServer("lobby").orElseThrow();
      try (Client client = Client.join(proxy.port(), "switcher")) {
        require(proxy.recorder.await(PlayerServerConnectedEvent.class, 1), "joined");
        Player player = proxy.runtime.player("switcher").orElseThrow();

        proxy.recorder.hook = event -> {
          if (event instanceof PlayerServerConnectEvent connect && connect.target().getName().equals("survival")) connect.setCancelled(true);
        };
        ConnectResult cancelled = player.connectWithResult(survivalView).get(10, TimeUnit.SECONDS);
        require(cancelled.status() == ConnectResult.Status.CANCELLED, "cancelled, got " + cancelled);
        require(survival.logins.get() == 0, "a cancelled switch opens no backend connection");
        require(proxy.recorder.of(PlayerServerSwitchFailedEvent.class).isEmpty(), "a cancelled switch is not a failed one");
        require(player.currentServer().name().equals("lobby"), "still on lobby");

        proxy.recorder.hook = event -> { };
        ConnectResult moved = player.connectWithResult(survivalView).get(15, TimeUnit.SECONDS);
        require(moved.successful(), "switched, got " + moved);
        require(player.currentServer().name().equals("survival"), "now on survival");
        PlayerServerConnectedEvent connected = proxy.recorder.of(PlayerServerConnectedEvent.class).getLast();
        require(connected.source().orElseThrow().getName().equals("lobby") && connected.target().getName().equals("survival"), "connected event");
        require(proxy.recorder.of(PlayerServerSwitchEvent.class).size() == 1, "one switch event for the switch, none for the join");
        require(player.connectWithResult(survivalView).get(5, TimeUnit.SECONDS).status() == ConnectResult.Status.ALREADY_CONNECTED, "already there");

        ConnectResult failed = player.connectWithResult(deadView).get(15, TimeUnit.SECONDS);
        require(failed.status() == ConnectResult.Status.FAILED && !failed.reason().isEmpty(), "a refusing backend fails with a reason, got " + failed);
        PlayerServerSwitchFailedEvent failure = proxy.recorder.of(PlayerServerSwitchFailedEvent.class).getLast();
        require(failure.target().getName().equals("dead") && failure.source().orElseThrow().getName().equals("survival"), "failed event names both ends");
        require(player.currentServer().name().equals("survival"), "left where it was");

        proxy.recorder.hook = event -> {
          if (event instanceof PlayerServerConnectEvent connect && connect.target().getName().equals("dead")) connect.setTarget(lobbyView);
        };
        require(deadView.connect(player).get(15, TimeUnit.SECONDS), "redirected switch succeeds");
        require(player.currentServer().name().equals("lobby"), "went where the listener sent it");
        proxy.recorder.hook = event -> { };

        var first = player.connectWithResult(proxy.runtime.servers().getServer("slow").orElseThrow());
        require(waitFor(() -> slow.logins.get() == 1, 5_000), "slow switch under way");
        ConnectResult busy = player.connectWithResult(survivalView).get(5, TimeUnit.SECONDS);
        require(busy.status() == ConnectResult.Status.IN_PROGRESS, "a second switch while one runs, got " + busy);
        require(first.get(15, TimeUnit.SECONDS).status() == ConnectResult.Status.FAILED, "the slow one times out");

        require(client.received(p -> id(p) == CHAT_OUT && text(p).contains("Connected to")).isEmpty(),
            "a plugin's connect tells the player nothing");
      }
    }
  }

  private static void aLostBackendFallsBackThroughTheSameEvents() throws Exception {
    try (Backend lobby = new Backend("lobby"); Backend survival = new Backend("survival"); Backend hub = new Backend("hub");
         Fixture proxy = new Fixture(List.of(lobby, survival, hub), List.of("lobby"), List.of("lobby", "survival", "hub"))) {
      proxy.recorder.hook = event -> {
        if (event instanceof PlayerServerConnectEvent connect && connect.source().isPresent() && connect.target().getName().equals("survival")) {
          connect.setCancelled(true);
        }
      };
      try (Client client = Client.join(proxy.port(), "faller")) {
        require(proxy.recorder.await(PlayerServerConnectedEvent.class, 1), "joined");
        lobby.drop();
        require(proxy.recorder.await(PlayerServerSwitchEvent.class, 1), "fell back, events " + proxy.recorder.names());
        require(survival.logins.get() == 0, "a cancelled fallback candidate is never dialled");
        require(hub.logins.get() == 1, "the next fallback is used");
        PlayerServerConnectedEvent connected = proxy.recorder.of(PlayerServerConnectedEvent.class).getLast();
        require(connected.source().orElseThrow().getName().equals("lobby") && connected.target().getName().equals("hub"), "connected event for the fallback");
        require(proxy.runtime.player("faller").orElseThrow().currentServer().name().equals("hub"), "player is on the fallback");
      }
    }
  }

  private static void pluginMessagesCanBeDroppedOrSent() throws Exception {
    try (Backend lobby = new Backend("lobby"); Fixture proxy = new Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"))) {
      proxy.recorder.hook = event -> {
        if (event instanceof PluginMessageEvent message && message.channel().endsWith("drop")) message.setCancelled(true);
      };
      try (Client client = Client.join(proxy.port(), "messenger")) {
        require(proxy.recorder.await(PlayerServerConnectedEvent.class, 1), "joined");
        Player player = proxy.runtime.player("messenger").orElseThrow();
        client.send(pluginMessage(PLUGIN_IN, "test:drop", new byte[] {1}));
        client.send(pluginMessage(PLUGIN_IN, "test:keep", new byte[] {2}));
        require(lobby.await(p -> channel(p).equals("test:keep")), "a plugin message nobody cancels is forwarded");
        require(lobby.received(p -> channel(p).equals("test:drop")).isEmpty(), "a cancelled one is not");

        lobby.send(pluginMessage(PLUGIN_OUT, "test:down-drop", new byte[] {3}));
        lobby.send(pluginMessage(PLUGIN_OUT, "test:down", new byte[] {4}));
        require(client.await(p -> channel(p).equals("test:down")), "backend to client forwarded");
        require(client.received(p -> channel(p).equals("test:down-drop")).isEmpty(), "backend to client cancelled");

        List<PluginMessageEvent> seen = proxy.recorder.of(PluginMessageEvent.class);
        require(seen.stream().anyMatch(e -> e.channel().equals("test:keep") && e.direction() == PluginMessageEvent.Direction.CLIENT_TO_PROXY
            && e.player() == player && e.data()[0] == 2), "client-side event");
        require(seen.stream().anyMatch(e -> e.channel().equals("test:down") && e.direction() == PluginMessageEvent.Direction.BACKEND_TO_PROXY),
            "backend-side event");

        require(player.sendPluginMessageToServer("test:api", new byte[] {5, 6}), "sent to the backend");
        require(lobby.await(p -> channel(p).equals("test:api") && java.util.Arrays.equals(data(p), new byte[] {5, 6})), "backend got it");
        require(proxy.runtime.servers().getServer("lobby").orElseThrow().sendPluginMessage("test:server", new byte[] {7}), "sent through the server");
        require(lobby.await(p -> channel(p).equals("test:server")), "backend got the server message");
        require(!proxy.runtime.servers().getServer("lobby").orElseThrow().players().isEmpty(), "server lists the player");
        player.sendPluginMessage("test:client", new byte[] {8});
        require(client.await(p -> channel(p).equals("test:client")), "client got the proxy's message");
      }
    }
  }

  private static void chatAndCommandEventsCanBeCancelled() throws Exception {
    try (Backend lobby = new Backend("lobby"); Fixture proxy = new Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"))) {
      proxy.recorder.hook = event -> {
        if (event instanceof PlayerChatEvent chat && chat.message().equals("secret")) chat.setCancelled(true);
        if (event instanceof CommandExecuteEvent command && command.command().startsWith("blocked")) command.setCancelled(true);
      };
      List<Object> handled = Collections.synchronizedList(new ArrayList<>());
      proxy.runtime.commands().register(proxy.owner, gg.tame.conduit.api.command.CommandManager.Command.builder("greet")
          .handler((source, arguments) -> { handled.add(source); handled.addAll(arguments); }).build());
      try (Client client = Client.join(proxy.port(), "chatter")) {
        require(proxy.recorder.await(PlayerServerConnectedEvent.class, 1), "joined");
        for (String line : List.of("hello", "secret", "/blocked now", "/backendcmd y", "/greet a b", "after")) client.send(chat(line));
        require(lobby.await(p -> chatText(p).equals("after")), "the last line arrived");
        List<String> relayed = lobby.received(p -> !chatText(p).isEmpty()).stream().map(NativeApiTests::chatText).toList();
        require(relayed.equals(List.of("hello", "/backendcmd y", "after")), "only what nobody cancelled reached the backend, got " + relayed);
        require(proxy.recorder.of(PlayerChatEvent.class).stream().map(PlayerChatEvent::message).toList().equals(List.of("hello", "secret", "after")),
            "chat events for chat only, got " + proxy.recorder.of(PlayerChatEvent.class).stream().map(PlayerChatEvent::message).toList());
        require(proxy.recorder.of(CommandExecuteEvent.class).stream().map(CommandExecuteEvent::command).toList()
            .equals(List.of("blocked now", "backendcmd y", "greet a b")), "command events for every command");
        Player player = proxy.runtime.player("chatter").orElseThrow();
        require(handled.size() == 3 && handled.get(0) == player && handled.subList(1, 3).equals(List.of("a", "b")),
            "a plugin command runs with the player as its source, got " + handled);
      }
    }
  }

  /** In Play this was a system chat line and a dropped socket: the player saw "Connection lost". */
  private static void disconnectShowsTheKickScreen() throws Exception {
    try (Backend lobby = new Backend("lobby"); Fixture proxy = new Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"))) {
      try (Client client = Client.join(proxy.port(), "kicked")) {
        require(proxy.recorder.await(PlayerServerConnectedEvent.class, 1), "joined");
        proxy.runtime.player("kicked").orElseThrow().disconnect(Text.of("Bye").color(TextColor.GOLD));
        require(client.await(p -> id(p) == DISCONNECT_OUT), "a Play disconnect packet");
        byte[] kick = client.received(p -> id(p) == DISCONNECT_OUT).getFirst();
        require(text(kick).contains("Bye") && text(kick).contains("gold"), "with the formatted reason, got " + text(kick));
        require(client.received(p -> id(p) == CHAT_OUT && text(p).contains("Bye")).isEmpty(), "not as chat");
        require(client.ends(), "then the connection ends");
      }
      require(proxy.recorder.await(PlayerDisconnectEvent.class, 1), "disconnect event");
    }
  }

  private static void aThrowingPermissionProviderDenies() throws Exception {
    try (Backend lobby = new Backend("lobby"); Fixture proxy = new Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"))) {
      proxy.runtime.setPermissionProvider(proxy.owner, (subject, node) -> {
        if (node.equals("boom")) throw new IllegalStateException("provider bug");
        return node.equals("yes");
      });
      try (Client client = Client.join(proxy.port(), "perms")) {
        require(proxy.recorder.await(PlayerServerConnectedEvent.class, 1), "joined");
        Player player = proxy.runtime.player("perms").orElseThrow();
        require(player.hasPermission("yes") && !player.hasPermission("no"), "the plugin's provider answers for players");
        require(!player.hasPermission("boom"), "a provider that throws denies instead of failing the caller");
      }
    }
  }

  /**
   * ProxyShutdownEvent used to fire as the serving thread wound down, while close() was already
   * disabling plugins on another: a plugin was usually gone before it heard the proxy was stopping.
   */
  private static void proxyLifecycleReachesPluginsInOrder() throws Exception {
    Path root = TempFiles.dir("conduit-native-lifecycle");
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    CommandApiTests.buildPluginJar(plugins.resolve("watcher.jar"), "watcher", "watcher.WatcherPlugin", 1, """
        package watcher;
        import gg.tame.conduit.api.event.Subscribe;
        import gg.tame.conduit.api.event.proxy.ProxyShutdownEvent;
        import gg.tame.conduit.api.event.proxy.ProxyStartEvent;
        import gg.tame.conduit.tests.NativeApiTests;
        public final class WatcherPlugin extends gg.tame.conduit.api.plugin.ConduitPlugin {
          @Override public void onEnable() { proxy().events().register(this, this); }
          @Subscribe public void start(ProxyStartEvent event) { NativeApiTests.signal("start"); }
          @Subscribe public void stop(ProxyShutdownEvent event) { NativeApiTests.signal("shutdown:" + proxy().plugins().plugin("watcher").isPresent()); }
          @Override public void onDisable() { NativeApiTests.signal("disable"); }
        }
        """);
    SIGNALS.clear();
    try (Backend lobby = new Backend("lobby");
         Fixture proxy = new Fixture(List.of(lobby), List.of("lobby"), List.of(), plugins, OFFLINE, null)) {
      require(waitFor(() -> SIGNALS.contains("start"), 10_000), "ProxyStartEvent reached the plugin");
      try (Client client = Client.join(proxy.port(), "stayer")) {
        require(proxy.recorder.await(PlayerServerConnectedEvent.class, 1), "joined");
        require(!proxy.runtime.shuttingDown(), "running");
        proxy.runtime.shutdown();
        require(client.await(p -> id(p) == DISCONNECT_OUT), "the shutdown kicks with a disconnect screen");
        require(proxy.serving.join(Duration.ofSeconds(15)), "serve() returned: the listener really closed");
        require(waitFor(() -> SIGNALS.contains("disable"), 10_000), "the plugin was disabled, signals " + SIGNALS);
        require(proxy.runtime.shuttingDown(), "reports shutting down");
      }
    }
    require(SIGNALS.equals(List.of("start", "shutdown:true", "disable")),
        "start once, shutdown while still enabled, then disable, got " + SIGNALS);
  }

  /**
   * Every ping was answered "Conduit", 0 of 0, no sample, no icon, whatever was configured or online,
   * and the description was escaped for quotes and backslashes only, so a line break broke the JSON.
   */
  private static void theServerListShowsTheConfiguredEntry() throws Exception {
    StatusSettings status = new StatusSettings(StatusSettings.parseMotd("&bWelcome\\nline \"two\"\t"), 250,
        Optional.of("data:image/png;base64,AAAA"));
    try (Backend lobby = new Backend("lobby"); Fixture proxy = new Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"), status)) {
      String empty = ping(proxy.port(), "localhost");
      require(empty.contains("\"players\":{\"max\":250,\"online\":0}"), "the configured max and nobody online, got " + empty);
      try (Client client = Client.join(proxy.port(), "listed")) {
        require(proxy.recorder.await(PlayerServerConnectedEvent.class, 1), "joined");
        Player player = proxy.runtime.player("listed").orElseThrow();
        String json = ping(proxy.port(), "localhost");
        require(json.contains("\"players\":{\"max\":250,\"online\":1,\"sample\":[{\"name\":\"listed\",\"id\":\"" + player.uniqueId() + "\"}]}"),
            "the real online count and a sample of who is on, got " + json);
        require(json.contains("\"description\":{\"text\":\"Welcome\\nline \\\"two\\\"\\t\\u0001\",\"color\":\"aqua\"}"),
            "the configured MOTD as a component, every control character escaped, got " + json);
        require(json.contains("\"favicon\":\"data:image/png;base64,AAAA\"") && json.contains("\"protocol\":47}"), "icon and version, got " + json);
        require(gg.tame.conduit.protocol.text.ComponentCodec.parseJson(json) instanceof java.util.Map<?, ?>, "and it is JSON a client can read");
      }
      var defaults = proxy.runtime.serverListDefaults();
      require(defaults.description().equals(status.motd()) && defaults.maxPlayers() == 250 && defaults.favicon().equals(status.favicon()),
          "plugins can read the configured defaults, got " + defaults);
    }
  }

  private static void pingListenersCanRewriteCancelAndSurviveAThrowingOne() throws Exception {
    try (Backend lobby = new Backend("lobby"); Fixture proxy = new Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"))) {
      proxy.runtime.events().register(new TestPlugin("careless"), new Object() {
        @Subscribe(order = Subscribe.Order.FIRST) public void boom(ServerListPingEvent event) { event.setDescription(null); }
      });
      String untouched = ping(proxy.port(), "localhost");
      require(untouched.contains("\"description\":{\"text\":\"Conduit\"}") && untouched.contains("\"max\":100,\"online\":0"),
          "a throwing listener still leaves the default answer, got " + untouched);
      UUID ghost = new UUID(1, 2);
      proxy.recorder.hook = event -> {
        if (event instanceof ServerListPingEvent ping) {
          ping.setDescription(Text.of("Rewritten").color(TextColor.GOLD));
          ping.setMaxPlayers(7);
          ping.setOnlinePlayers(3);
          ping.setSamplePlayers(List.of(new ServerListPingEvent.SamplePlayer("ghost", ghost)));
          ping.setVersionName("Custom \"build\"");
          ping.setVersionProtocol(9999);
          ping.setFavicon(Optional.of("data:image/png;base64,BBBB"));
        }
      };
      String json = ping(proxy.port(), "play.example.net\0FML3\0");
      require(json.equals("{\"version\":{\"name\":\"Custom \\\"build\\\"\",\"protocol\":9999},\"players\":{\"max\":7,\"online\":3,"
          + "\"sample\":[{\"name\":\"ghost\",\"id\":\"" + ghost + "\"}]},\"description\":{\"text\":\"Rewritten\",\"color\":\"gold\"},"
          + "\"favicon\":\"data:image/png;base64,BBBB\"}"), "whatever the listener set is what the client gets, got " + json);
      ServerListPingEvent seen = proxy.recorder.of(ServerListPingEvent.class).getLast();
      require(seen.virtualHost().equals(Optional.of("play.example.net")) && seen.virtualPort() == 25565 && seen.protocolVersion() == 47
          && seen.remoteAddress().getAddress().isLoopbackAddress(), "who asked, and through which host and port, Forge marker removed");

      proxy.recorder.hook = event -> { if (event instanceof ServerListPingEvent ping) ping.setPlayersHidden(true); };
      String hidden = ping(proxy.port(), "localhost");
      require(!hidden.contains("\"players\"") && hidden.contains("\"protocol\":47},\"description\":{\"text\":\"Conduit\"}"),
          "hidden players leave the counts out altogether, got " + hidden);

      proxy.recorder.hook = event -> { if (event instanceof ServerListPingEvent ping) ping.setCancelled(true); };
      require(ping(proxy.port(), "localhost") == null, "a cancelled ping is closed with no answer");
      require(proxy.recorder.of(ServerListPingEvent.class).size() == 4, "one event per ping");
    }
  }

  private static void aLiveBackendPingAsksTheBackend() throws Exception {
    try (Backend lobby = new Backend("lobby"); Fixture proxy = new Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"))) {
      // The proxy's own status answer stands in for a backend's.
      RegisteredServer self = proxy.runtime.servers().register("self", new InetSocketAddress("127.0.0.1", proxy.port()));
      ServerStatus live = self.ping().get(10, TimeUnit.SECONDS);
      require(live.online() && live.maxPlayers().orElse(-1) == 100 && live.onlinePlayers().orElse(-1) == 0, "asked now, got " + live);
      RegisteredServer gone = proxy.runtime.servers().register("gone", new InetSocketAddress("127.0.0.1", reservePort()));
      require(!gone.ping().get(10, TimeUnit.SECONDS).online(), "an unreachable backend pings offline rather than failing");
    }
  }

  /** A backend's kick was relayed and the session closed, with no say for plugins. */
  private static void aKickWhilePlayingReachesTheClientAsTheBackendWroteIt() throws Exception {
    try (Backend lobby = new Backend("lobby"); Fixture proxy = new Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"))) {
      proxy.runtime.events().register(new TestPlugin("careless"), new Object() {
        @Subscribe public void clear(PlayerKickedFromServerEvent event) { event.setResult(null); }
        @Subscribe(order = Subscribe.Order.LAST) public void boom(PlayerKickedFromServerEvent event) { throw new IllegalStateException("listener bug"); }
      });
      try (Client client = Client.join(proxy.port(), "kickee")) {
        require(proxy.recorder.await(PlayerServerConnectedEvent.class, 1), "joined");
        String reason = "{\"translate\":\"multiplayer.disconnect.kicked\",\"color\":\"red\"}";
        lobby.send(packet(DISCONNECT_OUT, output -> MinecraftOutput.string(output, reason)));
        require(client.await(p -> id(p) == DISCONNECT_OUT), "the kick reaches the client");
        require(text(client.received(p -> id(p) == DISCONNECT_OUT).getFirst()).equals(reason), "exactly as the backend wrote it, translation and all");
        require(client.ends(), "and the session ends");
        PlayerKickedFromServerEvent kicked = proxy.recorder.of(PlayerKickedFromServerEvent.class).getFirst();
        require(!kicked.duringConnect() && kicked.server().getName().equals("lobby"), "kicked from the server it was on");
        require(kicked.reason().orElseThrow().equals(Text.translatable("multiplayer.disconnect.kicked").color(TextColor.RED)),
            "with the reason as Text, translation kept, got " + kicked.reason());
        require(kicked.result() instanceof KickResult.Disconnect, "a listener that sets null or throws leaves the default");
      }
    }
  }

  /**
   * A backend shutting down is the commonest event on a network, and it is a kick: the server says
   * "Server closed" to everyone on it and goes. Passing that on threw the player off the proxy
   * entirely, so restarting one server disconnected everyone who happened to be on it.
   */
  private static void aShuttingDownBackendMovesThePlayerRatherThanDisconnectingThem() throws Exception {
    try (Backend lobby = new Backend("lobby"); Backend survival = new Backend("survival");
         Fixture proxy = new Fixture(List.of(lobby, survival), List.of("lobby"), List.of("lobby", "survival"))) {
      try (Client client = Client.join(proxy.port(), "stayer")) {
        require(proxy.recorder.await(PlayerServerConnectedEvent.class, 1), "joined the lobby");
        // Exactly what a server sends on the way down, with nobody listening for the event.
        lobby.send(packet(DISCONNECT_OUT, output -> MinecraftOutput.string(output, "{\"text\":\"Server closed\"}")));
        require(proxy.recorder.await(PlayerServerSwitchEvent.class, 1), "moved instead, events " + proxy.recorder.names());
        require(proxy.runtime.player("stayer").orElseThrow().currentServer().name().equals("survival"),
            "onto the next server that would take them");
        require(survival.logins.get() == 1, "which was actually dialled");
        require(client.received(p -> id(p) == DISCONNECT_OUT).isEmpty(),
            "and the player is never shown the kick screen");
        require(client.await(p -> id(p) == CHAT_OUT && text(p).contains("Server closed")),
            "but is told why they moved");
      }
    }
  }

  private static void aKickListenerCanRedirectThePlayer() throws Exception {
    try (Backend lobby = new Backend("lobby"); Backend survival = new Backend("survival");
         Fixture proxy = new Fixture(List.of(lobby, survival), List.of("lobby"), List.of("lobby"))) {
      RegisteredServer survivalView = proxy.runtime.servers().getServer("survival").orElseThrow();
      proxy.recorder.hook = event -> {
        if (event instanceof PlayerKickedFromServerEvent kicked) kicked.setResult(new KickResult.Redirect(survivalView, Optional.of(Text.of("Moved you"))));
      };
      try (Client client = Client.join(proxy.port(), "redirected")) {
        require(proxy.recorder.await(PlayerServerConnectedEvent.class, 1), "joined");
        lobby.send(packet(DISCONNECT_OUT, output -> MinecraftOutput.string(output, "{\"text\":\"Restarting\"}")));
        require(proxy.recorder.await(PlayerServerSwitchEvent.class, 1), "moved, events " + proxy.recorder.names());
        require(proxy.runtime.player("redirected").orElseThrow().currentServer().name().equals("survival") && survival.logins.get() == 1,
            "to the server the listener named");
        require(client.await(p -> id(p) == CHAT_OUT && text(p).contains("Moved you")), "and told what the listener said");
        require(client.received(p -> id(p) == DISCONNECT_OUT).isEmpty(), "never shown the kick");
      }
    }
  }

  /** A backend refusing a switch lost its reason: the player heard only that the server was unavailable. */
  private static void aRefusedSwitchKeepsThePlayerAndTellsThem() throws Exception {
    try (Backend lobby = new Backend("lobby"); Backend strict = new Backend("strict");
         Fixture proxy = new Fixture(List.of(lobby, strict), List.of("lobby"), List.of("lobby"))) {
      strict.refuseWith = "{\"text\":\"You are not whitelisted\"}";
      RegisteredServer strictView = proxy.runtime.servers().getServer("strict").orElseThrow();
      try (Client client = Client.join(proxy.port(), "outsider")) {
        require(proxy.recorder.await(PlayerServerConnectedEvent.class, 1), "joined");
        Player player = proxy.runtime.player("outsider").orElseThrow();
        ConnectResult result = player.connectWithResult(strictView).get(15, TimeUnit.SECONDS);
        require(result.status() == ConnectResult.Status.FAILED && result.reason().contains("You are not whitelisted"),
            "the switch fails with the backend's reason, got " + result);
        require(player.currentServer().name().equals("lobby"), "the player stays where they were");
        require(client.await(p -> id(p) == CHAT_OUT && text(p).contains("You are not whitelisted")), "and is told why");
        PlayerKickedFromServerEvent kicked = proxy.recorder.of(PlayerKickedFromServerEvent.class).getFirst();
        require(kicked.duringConnect() && kicked.server() == strictView && kicked.result() instanceof KickResult.Notify,
            "a refused switch is a kick during connect, Notify by default");
        require(proxy.recorder.names(PlayerServerSwitchFailedEvent.class, PlayerKickedFromServerEvent.class)
            .equals(List.of("PlayerServerSwitchFailedEvent", "PlayerKickedFromServerEvent")), "reported, then decided, got " + proxy.recorder.names());

        proxy.recorder.hook = event -> {
          if (event instanceof PlayerKickedFromServerEvent refused) refused.setResult(new KickResult.Disconnect(Text.of("Go home")));
        };
        player.connectWithResult(strictView).get(15, TimeUnit.SECONDS);
        require(client.await(p -> id(p) == DISCONNECT_OUT && text(p).contains("Go home")), "a listener can make the refusal end the session");
        require(client.ends(), "and it does");
      }
    }
  }

  /**
   * A first server's refusal was relayed straight to a client still logging in, which closed on it
   * with the next candidate never tried; and with none left the player got the generic message.
   */
  private static void aRefusedFirstServerMovesOnAndExplains() throws Exception {
    try (Backend lobby = new Backend("lobby"); Backend survival = new Backend("survival");
         Fixture proxy = new Fixture(List.of(lobby, survival), List.of("lobby", "survival"), List.of("lobby"))) {
      lobby.refuseWith = "{\"text\":\"Lobby is full\"}";
      try (Client client = Client.join(proxy.port(), "walker")) {
        require(proxy.recorder.await(PlayerServerConnectedEvent.class, 1), "joined");
        require(proxy.runtime.player("walker").orElseThrow().currentServer().name().equals("survival"), "the next candidate took the player");
        PlayerServerSwitchFailedEvent failed = proxy.recorder.of(PlayerServerSwitchFailedEvent.class).getFirst();
        require(failed.source().isEmpty() && failed.target().getName().equals("lobby"), "a failed first server is reported, with no source");
        PlayerKickedFromServerEvent kicked = proxy.recorder.of(PlayerKickedFromServerEvent.class).getFirst();
        require(kicked.duringConnect() && kicked.result() instanceof KickResult.Notify
            && kicked.reason().orElseThrow().plain().equals("Lobby is full"), "and decided on, Notify by default");
      }
      String untranslated = "{\"translate\":\"multiplayer.disconnect.not_whitelisted\"}";
      survival.refuseWith = untranslated;
      try (Client refused = Client.open(proxy.port(), "turned-away")) {
        byte[] reply = refused.readDirect();
        require(id(reply) == 0, "a Login Disconnect, got id " + id(reply));
        require(text(reply).equals(untranslated), "the last refusal as its server wrote it, not the generic message, got " + text(reply));
      }
      // Let in by PlayerLoginEvent but taken by no server: its listeners heard nothing more before.
      require(proxy.recorder.await(PlayerDisconnectEvent.class, 2), "a disconnect event for each player let in");
      var left = proxy.recorder.of(PlayerDisconnectEvent.class);
      require(left.stream().anyMatch(event -> event.player().username().equals("walker") && event.completedLogin())
          && left.stream().anyMatch(event -> event.player().username().equals("turned-away") && !event.completedLogin()),
          "one who joined and one who never did, got " + left);
    }
  }

  /** Nothing of the client's settings reached the API, so a plugin localising messages always had English. */
  private static void theClientsLanguageIsKnownOnceItSendsItsSettings() throws Exception {
    try (Backend lobby = new Backend("lobby"); Fixture proxy = new Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"))) {
      try (Client client = Client.join(proxy.port(), "linguist")) {
        require(proxy.recorder.await(PlayerServerConnectedEvent.class, 1), "joined");
        Player player = proxy.runtime.player("linguist").orElseThrow();
        require(player.locale().isEmpty(), "no language before the client says");
        // 1.8 Client Settings: language, view distance, chat mode, chat colours, skin parts.
        client.send(packet(P47.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CLIENT_INFORMATION), output -> {
          MinecraftOutput.string(output, "de_de");
          output.writeByte(8); output.writeByte(0); output.writeBoolean(true); output.writeByte(0x7F);
        }));
        require(waitFor(() -> player.locale().isPresent(), 5_000), "the language once the client sends its settings");
        require(player.locale().orElseThrow().equals(java.util.Locale.GERMANY), "de_de as de-DE, got " + player.locale());
      }
    }
  }

  /** A raw 1.8 server-list ping: the status JSON, or null when the proxy closed without answering. */
  private static String ping(int port, String host) throws Exception {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(10_000);
      MinecraftFrames.write(socket.getOutputStream(), new Handshake(47, host, 25565, 1).encode());
      MinecraftFrames.write(socket.getOutputStream(), new byte[] {0});
      byte[] response;
      try { response = MinecraftFrames.read(socket.getInputStream(), 1 << 20); }
      catch (java.net.SocketTimeoutException stalled) { throw new AssertionError("no answer and no close"); }
      catch (IOException closed) { return null; }
      require(id(response) == 0, "a status response, got id " + id(response));
      String json = text(response);
      MinecraftFrames.write(socket.getOutputStream(), packet(1, output -> output.writeLong(42)));
      byte[] pong = MinecraftFrames.read(socket.getInputStream(), 64);
      require(id(pong) == 1 && java.nio.ByteBuffer.wrap(pong, 1, 8).getLong() == 42, "the pong echoes the nonce");
      return json;
    }
  }

  private static final List<String> SIGNALS = Collections.synchronizedList(new ArrayList<>());
  public static void signal(String value) { SIGNALS.add(value); }

  // --- harness ------------------------------------------------------------------------------

  static final ProtocolDefinition P47 = ProtocolDefinition.forVersion(47);
  static final int CHAT_OUT = P47.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SYSTEM_CHAT);
  static final int DISCONNECT_OUT = P47.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DISCONNECT);
  private static final int PLUGIN_OUT = P47.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLUGIN_MESSAGE);
  private static final int PLUGIN_IN = P47.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_PLUGIN_MESSAGE);
  static final int CHAT_IN = P47.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT_COMMAND);
  private static final AuthenticationSettings OFFLINE = AuthenticationSettings.offline();

  /** Records every event the proxy fires, and lets a test act on them as a plugin would. */
  public static final class Recorder {
    private final List<Event> seen = Collections.synchronizedList(new ArrayList<>());
    volatile Consumer<Event> hook = event -> { };
    @Subscribe public void any(Event event) {
      seen.add(event);
      hook.accept(event);
    }
    <E> List<E> of(Class<E> type) {
      synchronized (seen) { return seen.stream().filter(type::isInstance).map(type::cast).toList(); }
    }
    List<String> names() {
      synchronized (seen) { return seen.stream().map(event -> event.getClass().getSimpleName()).toList(); }
    }
    List<String> names(Class<?>... types) {
      synchronized (seen) {
        return seen.stream().filter(event -> java.util.Arrays.stream(types).anyMatch(type -> type.isInstance(event)))
            .map(event -> event.getClass().getSimpleName()).toList();
      }
    }
    boolean await(Class<?> type, int count) throws InterruptedException {
      return waitFor(() -> of(type).size() >= count, 10_000);
    }
  }

  static final class Fixture implements AutoCloseable {
    final MinecraftProxy proxy;
    final ConduitRuntime runtime;
    private final Thread serving;
    final Recorder recorder = new Recorder();
    private final Plugin owner = new TestPlugin("probe");
    Fixture(List<Backend> backends, List<String> initial, List<String> fallback) throws Exception {
      this(backends, initial, fallback, TempFiles.dir("conduit-native-api").resolve("plugins"), OFFLINE, null);
    }
    Fixture(List<Backend> backends, List<String> initial, List<String> fallback, AuthenticationSettings auth, PlayerAuthenticator authenticator) throws Exception {
      this(backends, initial, fallback, TempFiles.dir("conduit-native-api").resolve("plugins"), auth, authenticator);
    }
    Fixture(List<Backend> backends, List<String> initial, List<String> fallback, StatusSettings status) throws Exception {
      this(backends, initial, fallback, TempFiles.dir("conduit-native-api").resolve("plugins"), OFFLINE, null, status);
    }
    Fixture(List<Backend> backends, List<String> initial, List<String> fallback, Path plugins,
            AuthenticationSettings auth, PlayerAuthenticator authenticator) throws Exception {
      this(backends, initial, fallback, plugins, auth, authenticator, null);
    }
    Fixture(List<Backend> backends, List<String> initial, List<String> fallback, Path plugins,
            AuthenticationSettings auth, PlayerAuthenticator authenticator, StatusSettings status) throws Exception {
      this(backends, initial, fallback, plugins, auth, authenticator, status, new HealthSettings(false, 10_000, 1_500, 3, 2));
    }
    Fixture(List<Backend> backends, List<String> initial, List<String> fallback, HealthSettings health) throws Exception {
      this(backends, initial, fallback, TempFiles.dir("conduit-native-api").resolve("plugins"), OFFLINE, null, null, health);
    }
    Fixture(List<Backend> backends, List<String> initial, List<String> fallback, gg.tame.conduit.config.MessagingSettings messaging) throws Exception {
      this(backends, initial, fallback, TempFiles.dir("conduit-native-api").resolve("plugins"), OFFLINE, null, null,
          new HealthSettings(false, 10_000, 1_500, 3, 2), messaging);
    }
    Fixture(List<Backend> backends, List<String> initial, List<String> fallback, Path plugins,
            AuthenticationSettings auth, PlayerAuthenticator authenticator, StatusSettings status, HealthSettings health) throws Exception {
      this(backends, initial, fallback, plugins, auth, authenticator, status, health, null);
    }
    Fixture(List<Backend> backends, List<String> initial, List<String> fallback, Path plugins,
            AuthenticationSettings auth, PlayerAuthenticator authenticator, StatusSettings status, HealthSettings health,
            gg.tame.conduit.config.MessagingSettings messaging) throws Exception {
      OpsSettings ops = new OpsSettings(OpsSettings.CURRENT_SCHEMA, null, health, null, null, null, null, null, status,
          null, null, messaging, null);
      ConduitConfiguration configuration = new ConduitConfiguration(new InetSocketAddress("127.0.0.1", reservePort()), 1 << 20,
          ForwardingMode.NONE, Optional.empty(), backends.stream().map(Backend::server).toList(), initial, fallback,
          auth, Optional.empty(), ops);
      proxy = new MinecraftProxy(configuration,
          authenticator != null ? authenticator : gg.tame.conduit.auth.Authenticators.create(auth),
          gg.tame.conduit.crypto.RsaKeys.generate(), plugins);
      runtime = proxy.runtime();
      runtime.events().register(owner, recorder);
      serving = Thread.ofPlatform().daemon().name("test-serve").start(() -> {
        try { proxy.serve(); } catch (Exception ignored) { }
      });
    }
    int port() throws IOException { return proxy.port(); }
    @Override public void close() throws Exception {
      proxy.close();
      serving.join(10_000);
    }
  }

  /** A 1.8.9 backend: accepts the login, sends Join Game, then records every packet it is sent. */
  static final class Backend implements AutoCloseable {
    private final String name;
    private final ServerSocket listener = new ServerSocket(0);
    final AtomicInteger logins = new AtomicInteger();
    private final List<byte[]> received = Collections.synchronizedList(new ArrayList<>());
    private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<>());
    private volatile Socket current;
    volatile boolean refuse;
    /** A reason to refuse every login with, as a Login Disconnect, the way a whitelisted server does. */
    volatile String refuseWith;
    volatile boolean silent;
    Backend(String name) throws IOException {
      this.name = name;
      Thread.ofPlatform().daemon().name("test-backend-" + name).start(() -> {
        try {
          while (true) {
            Socket socket = listener.accept();
            sockets.add(socket);
            Thread.ofPlatform().daemon().start(() -> serve(socket));
          }
        } catch (IOException closed) { }
      });
    }
    BackendServer server() { return new BackendServer(name, new InetSocketAddress("127.0.0.1", listener.getLocalPort())); }
    private void serve(Socket socket) {
      try (socket) {
        InputStream in = socket.getInputStream();
        Handshake handshake = Handshake.decode(MinecraftFrames.read(in, 4096));
        if (handshake.nextState() != 2) return;
        MinecraftFrames.read(in, 4096);
        logins.incrementAndGet();
        String refusal = refuseWith;
        if (refusal != null) { write(socket, packet(0, output -> MinecraftOutput.string(output, refusal))); return; }
        if (refuse) return;
        if (silent) { in.transferTo(OutputStream.nullOutputStream()); return; }
        current = socket;
        write(socket, loginSuccess());
        write(socket, joinGame());
        while (true) received.add(MinecraftFrames.read(in, 1 << 20));
      } catch (IOException ended) { }
    }
    void send(byte[] packet) throws IOException { write(current, packet); }
    /** To every player on this backend at once, as a server kicks everyone while it stops. */
    void sendAll(byte[] packet) throws IOException {
      synchronized (sockets) { for (Socket socket : sockets) if (!socket.isClosed()) write(socket, packet); }
    }
    /** The backend going away under a player, as a crash or restart would. */
    void drop() throws IOException { current.close(); }
    List<byte[]> received(Predicate<byte[]> match) {
      synchronized (received) { return received.stream().filter(match).toList(); }
    }
    boolean await(Predicate<byte[]> match) throws InterruptedException { return waitFor(() -> !received(match).isEmpty(), 10_000); }
    @Override public void close() throws IOException {
      listener.close();
      synchronized (sockets) { for (Socket socket : sockets) socket.close(); }
    }
    private static void write(Socket socket, byte[] packet) throws IOException {
      synchronized (socket) { MinecraftFrames.write(socket.getOutputStream(), packet); }
    }
  }

  /** A 1.8.9 client. After {@link #join} a reader thread keeps every packet the proxy sends it. */
  static final class Client implements AutoCloseable {
    private final Socket socket;
    private final List<byte[]> received = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean reading;
    private volatile boolean ended;
    private Client(Socket socket) { this.socket = socket; }
    static Client open(int port, String name) throws IOException {
      Socket socket = new Socket("127.0.0.1", port);
      socket.setSoTimeout(15_000);
      MinecraftFrames.write(socket.getOutputStream(), new Handshake(47, "localhost", 25565, 2).encode());
      MinecraftFrames.write(socket.getOutputStream(), loginStart(name));
      return new Client(socket);
    }
    static Client join(int port, String name) throws IOException {
      Client client = open(port, name);
      byte[] success = client.readDirect();
      require(PlayPackets.packetId(success) == 2, name + " got Login Success, got id " + PlayPackets.packetId(success));
      client.reading = true;
      Thread.ofPlatform().daemon().name("test-client-" + name).start(() -> {
        try { while (true) client.received.add(MinecraftFrames.read(client.socket.getInputStream(), 1 << 20)); }
        catch (IOException closed) { client.ended = true; }
      });
      return client;
    }
    byte[] readDirect() throws IOException { return MinecraftFrames.read(socket.getInputStream(), 1 << 20); }
    void send(byte[] packet) throws IOException { MinecraftFrames.write(socket.getOutputStream(), packet); }
    List<byte[]> received(Predicate<byte[]> match) {
      synchronized (received) { return received.stream().filter(match).toList(); }
    }
    boolean await(Predicate<byte[]> match) throws InterruptedException { return waitFor(() -> !received(match).isEmpty(), 10_000); }
    /** True once the proxy has closed the connection. */
    boolean ends() throws Exception {
      if (reading) return waitFor(() -> ended, 10_000);
      socket.setSoTimeout(10_000);
      try {
        while (true) MinecraftFrames.read(socket.getInputStream(), 1 << 20);
      } catch (java.net.SocketTimeoutException stalled) { return false; }
      catch (IOException closed) { return true; }
    }
    @Override public void close() throws IOException { socket.close(); }
  }

  record TestPlugin(String id) implements Plugin {
    @Override public PluginDescription description() { return new PluginDescription(id, id, "1.0", "Main", 1, List.of()); }
    @Override public ConduitProxy proxy() { return null; }
    @Override public Logger getLogger() { return Logger.getLogger("test." + id); }
    @Override public Path dataDirectory() { return Path.of("."); }
    @Override public Scheduler getScheduler() { return null; }
    @Override public void onLoad() { }
    @Override public void onEnable() { }
    @Override public void onDisable() { }
  }

  private static final class Probe implements Event { volatile boolean cancelled; }

  interface Body { void write(DataOutputStream output) throws Exception; }

  static byte[] packet(int id, Body body) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, id);
      body.write(output);
    } catch (IOException failed) {
      throw failed;
    } catch (Exception failed) {
      throw new IOException(failed);
    }
    return bytes.toByteArray();
  }
  static int id(byte[] packet) {
    try { return PlayPackets.packetId(packet); } catch (IOException unreadable) { return -1; }
  }
  static byte[] loginStart(String name) throws IOException { return packet(0, output -> MinecraftOutput.string(output, name)); }
  private static byte[] loginSuccess() throws IOException {
    return packet(2, output -> { MinecraftOutput.string(output, new UUID(0, 1).toString()); MinecraftOutput.string(output, "backend"); });
  }
  private static byte[] joinGame() throws IOException {
    return packet(0x01, output -> {
      output.writeInt(1); output.writeByte(0); output.writeByte(0); output.writeByte(0); output.writeByte(20);
      MinecraftOutput.string(output, "flat"); output.writeBoolean(false);
    });
  }
  static byte[] chat(String line) throws IOException { return packet(CHAT_IN, output -> MinecraftOutput.string(output, line)); }
  static byte[] pluginMessage(int id, String channel, byte[] data) throws IOException {
    return new PluginMessage(channel, data).encode(id);
  }
  /** The clientbound 1.8 plugin-message id, for a test in another class driving these fixtures. */
  static int pluginOut() { return PLUGIN_OUT; }
  static String channelOf(byte[] packet) { return channel(packet); }
  static byte[] dataOf(byte[] packet) { return data(packet); }
  /** The channel of a 1.8 plugin message in either direction, or "" for any other packet. */
  private static String channel(byte[] packet) {
    try {
      int id = PlayPackets.packetId(packet);
      if (id != PLUGIN_IN && id != PLUGIN_OUT) return "";
      return PluginMessage.decodeBody(PlayPackets.body(packet), 1 << 20).channel();
    } catch (IOException | RuntimeException unreadable) { return ""; }
  }
  private static byte[] data(byte[] packet) {
    try { return PluginMessage.decodeBody(PlayPackets.body(packet), 1 << 20).data(); }
    catch (IOException unreadable) { return new byte[0]; }
  }
  /** The serverbound 1.8 chat line, or "" for any other packet. */
  static String chatText(byte[] packet) {
    try {
      if (PlayPackets.packetId(packet) != CHAT_IN) return "";
      return PlayPackets.chatCommand(packet);
    } catch (IOException | RuntimeException unreadable) { return ""; }
  }
  /** The leading JSON string of a packet: a disconnect reason or a 1.8 chat line. */
  static String text(byte[] packet) {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      MinecraftInput.varInt(input);
      return MinecraftInput.string(input, 1 << 16);
    } catch (IOException | RuntimeException unreadable) { return ""; }
  }

  static int reservePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
  }

  interface Condition { boolean holds() throws Exception; }
  static boolean waitFor(Condition condition, long millis) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
    while (System.nanoTime() < deadline) {
      try { if (condition.holds()) return true; } catch (Exception notYet) { }
      Thread.sleep(10);
    }
    try { return condition.holds(); } catch (Exception failed) { return false; }
  }

  static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
