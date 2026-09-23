// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.NativeApiTests.DISCONNECT_OUT;
import static gg.tame.conduit.tests.NativeApiTests.P47;
import static gg.tame.conduit.tests.NativeApiTests.chat;
import static gg.tame.conduit.tests.NativeApiTests.chatText;
import static gg.tame.conduit.tests.NativeApiTests.id;
import static gg.tame.conduit.tests.NativeApiTests.loginStart;
import static gg.tame.conduit.tests.NativeApiTests.packet;
import static gg.tame.conduit.tests.NativeApiTests.reservePort;
import static gg.tame.conduit.tests.NativeApiTests.text;
import static gg.tame.conduit.tests.NativeApiTests.waitFor;

import gg.tame.conduit.api.command.CommandManager.Command;
import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.event.Subscribe;
import gg.tame.conduit.api.event.player.PlayerAuthenticatedEvent;
import gg.tame.conduit.api.event.player.PlayerDisconnectEvent;
import gg.tame.conduit.api.event.player.PlayerDisconnectEvent.LoginStatus;
import gg.tame.conduit.api.event.player.PlayerLoginEvent;
import gg.tame.conduit.api.event.player.PlayerPostLoginEvent;
import gg.tame.conduit.api.event.player.PlayerSetupEvent;
import gg.tame.conduit.api.permission.PermissionProvider;
import gg.tame.conduit.api.permission.PermissionSubject;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.api.plugin.Plugin;
import gg.tame.conduit.auth.AuthenticationException;
import gg.tame.conduit.auth.PlayerAuthenticator;
import gg.tame.conduit.command.CommandGraphs;
import gg.tame.conduit.command.Permissions;
import gg.tame.conduit.config.AuthenticationMode;
import gg.tame.conduit.config.AuthenticationSettings;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.config.ForwardingMode;
import gg.tame.conduit.config.HealthSettings;
import gg.tame.conduit.config.MaintenanceSettings;
import gg.tame.conduit.config.OpsSettings;
import gg.tame.conduit.config.VersionGateSettings;
import gg.tame.conduit.login.PlayerProfile;
import gg.tame.conduit.network.MinecraftProxy;
import gg.tame.conduit.network.PacketTransport;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.runtime.ConduitRuntime;
import gg.tame.conduit.tests.NativeApiTests.Backend;
import gg.tame.conduit.tests.NativeApiTests.Client;
import gg.tame.conduit.tests.NativeApiTests.Recorder;
import gg.tame.conduit.tests.NativeApiTests.TestPlugin;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A login in three stages, against a real proxy with scripted 1.8 clients: what the proxy refuses on
 * its own account (no plugin ever sees the player), then the player set up for plugins, then the
 * decisions plugins can take part in -- maintenance and its bypass permission, then
 * PlayerLoginEvent. Every player who was set up gets exactly one PlayerDisconnectEvent, however the
 * login ends. The Velocity adapter's side of it is a compiled LuckPerms-shaped plugin.
 */
public final class LoginLifecycleTests {
  public static void main(String[] a) throws Exception { run(); }

  public static void run() throws Exception {
    maintenanceOffLetsInAndTheDefaultProviderLetsNobodyThrough();
    theAllowlistAndANativePermissionPluginGrantTheBypass();
    aBrokenOrMissingProviderRefusesTheBypassButNotTheAllowlist();
    aLoginThatEndsWhileListenersHoldItEndsOnce();
    aShutdownDuringALoginRefusesIt();
    securityRefusalsComeBeforeAnyPluginSeesThePlayer();
    aDoubleSlashReachesASlashNamedCommandOnly();
    aVelocityPermissionPluginDecidesTheBypassAndIsReleased();
    aSecondLoginOfAConnectedPlayerIsRefused();
    anOnlineAccountIsOneSessionWhateverItsName();
    aRejoinWaitsForTheLastSessionToEnd();
    loginsAtTheSameInstantLetOneIn();
    kickExistingPlayersHandsTheSessionToTheNewLogin();
    aVelocityPluginHearsADisplacedLoginAsConflicting();
    System.out.println("LoginLifecycleTests OK");
  }

  private static final String KICK = "Closed for maintenance";
  private static final String BYPASS = Permissions.MAINTENANCE_BYPASS;

  private static MaintenanceSettings maintenance(boolean on, String... allowlist) {
    return new MaintenanceSettings(true, on, KICK, null, Set.of(allowlist));
  }

  // --- maintenance ---------------------------------------------------------------------------

  private static void maintenanceOffLetsInAndTheDefaultProviderLetsNobodyThrough() throws Exception {
    try (Backend lobby = new Backend("lobby"); Proxy proxy = new Proxy(lobby, maintenance(false))) {
      try (Client client = Client.join(proxy.port(), "Anyone")) {
        require(proxy.recorder.await(PlayerPostLoginEvent.class, 1), "maintenance off: in");
      }
      require(proxy.recorder.await(PlayerDisconnectEvent.class, 1), "left");
      require(story(proxy, "Anyone").equals(List.of("PlayerSetupEvent", "PlayerLoginEvent", "PlayerPostLoginEvent", "PlayerDisconnectEvent")),
          "set up, decided, joined, left, got " + story(proxy, "Anyone"));
      require(ended(proxy, "Anyone") == LoginStatus.SUCCESSFUL_LOGIN, "a complete login");

      proxy.runtime.maintenance().enable();
      // Conduit's default provider grants no Conduit node, the bypass included; and even were it to,
      // manages() keeps it from letting anyone through maintenance.
      require(!proxy.runtime.permissions().hasPermission(null, BYPASS), "the default does not grant the bypass");
      refusedByMaintenance(proxy, "Nobody");
      require(lobby.logins.get() == 1, "the refused player never reached a backend");
      require(story(proxy, "Nobody").equals(List.of("PlayerSetupEvent", "PlayerDisconnectEvent")),
          "set up, then refused before PlayerLoginEvent, and told once, got " + story(proxy, "Nobody"));
      require(ended(proxy, "Nobody") == LoginStatus.CANCELLED_BY_PROXY, "refused by the proxy");
    }
  }

  private static void theAllowlistAndANativePermissionPluginGrantTheBypass() throws Exception {
    try (Backend lobby = new Backend("lobby"); Proxy proxy = new Proxy(lobby, maintenance(true, "Listed"))) {
      TablePermissions table = new TablePermissions(Map.of("Staff", Set.of(BYPASS), "Admin", Set.of(Permissions.CONDUIT_ADMIN)));
      proxy.runtime.events().register(proxy.owner, table);
      proxy.runtime.setPermissionProvider(proxy.owner, table);
      for (String name : List.of("Staff", "Admin", "Listed")) {
        try (Client client = Client.join(proxy.port(), name)) {
          require(waitFor(() -> story(proxy, name).contains("PlayerPostLoginEvent"), 10_000), name + " is let through");
        }
      }
      refusedByMaintenance(proxy, "Guest");
      require(table.asked.stream().anyMatch(line -> line.startsWith("Staff:")) && table.asked.stream().allMatch(line -> line.endsWith(":loaded")),
          "the provider was asked only about players it had loaded in PlayerSetupEvent, got " + table.asked);
      // Only about the bypass: who is out of /gban's reach is noted after every login, that one included.
      require(table.asked.stream().noneMatch(line -> line.startsWith("Listed:" + BYPASS)), "the allowlist is decided without asking");
      require(waitFor(() -> proxy.recorder.of(PlayerDisconnectEvent.class).size() == 4, 10_000), "everyone left");
      require(waitFor(table.loaded::isEmpty, 10_000), "the plugin released every player, the refused one included, left " + table.loaded);
      require(lobby.logins.get() == 3, "only the three let through reached a backend");
    }
  }

  private static void aBrokenOrMissingProviderRefusesTheBypassButNotTheAllowlist() throws Exception {
    try (Backend lobby = new Backend("lobby"); Proxy proxy = new Proxy(lobby, maintenance(true, "Listed", "Listed2"))) {
      proxy.runtime.setPermissionProvider(proxy.owner, (subject, node) -> { throw new IllegalStateException("permission database down"); });
      String logged = capturingErr(() -> refusedByMaintenance(proxy, "Staff"));
      require(logged.contains("bypass maintenance") && logged.contains("permission database down"), "the provider's failure is logged, got " + logged);
      try (Client client = Client.join(proxy.port(), "Listed")) {
        require(waitFor(() -> story(proxy, "Listed").contains("PlayerPostLoginEvent"), 10_000), "the allowlist still works");
      }

      // Listed's leave asks the provider in force who is out of /gban's reach; let it go before the next one arrives.
      require(proxy.recorder.await(PlayerDisconnectEvent.class, 2), "Staff and Listed left");
      // The plugin that granted the bypass is gone, and Conduit's default is back in its place.
      Plugin perms = new TestPlugin("perms");
      TablePermissions table = new TablePermissions(Map.of("Keeper", Set.of(BYPASS)));
      proxy.runtime.events().register(perms, table);
      proxy.runtime.setPermissionProvider(perms, table);
      proxy.runtime.pluginReleased(perms);
      refusedByMaintenance(proxy, "Keeper");
      require(ended(proxy, "Keeper") == LoginStatus.CANCELLED_BY_PROXY && table.asked.isEmpty(), "refused, without asking the plugin that went");
      try (Client client = Client.join(proxy.port(), "Listed2")) {
        require(waitFor(() -> story(proxy, "Listed2").contains("PlayerPostLoginEvent"), 10_000), "and the allowlist still works");
      }
      require(lobby.logins.get() == 2, "only the allowlisted logins reached a backend");
    }
  }

  // --- a login that ends while plugins hold it -------------------------------------------------

  private static void aLoginThatEndsWhileListenersHoldItEndsOnce() throws Exception {
    try (Backend lobby = new Backend("lobby"); Proxy proxy = new Proxy(lobby, maintenance(false))) {
      CountDownLatch holding = new CountDownLatch(2);
      CountDownLatch release = new CountDownLatch(1);
      proxy.recorder.hook = event -> {
        boolean hold = event instanceof PlayerSetupEvent setup && setup.player().username().equals("Quitter")
            || event instanceof PlayerLoginEvent login && login.player().username().equals("Leaver");
        if (hold) {
          holding.countDown();
          try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        if (event instanceof PlayerLoginEvent login && login.player().username().equals("Kicked")) login.player().disconnect("Not today");
      };
      Client quitter = Client.open(proxy.port(), "Quitter");
      Client leaver = Client.open(proxy.port(), "Leaver");
      require(holding.await(10, TimeUnit.SECONDS), "both logins are held by a listener");
      quitter.close();
      leaver.close();
      Thread.sleep(200);
      release.countDown();
      require(waitFor(() -> ended(proxy, "Quitter") != null && ended(proxy, "Leaver") != null, 10_000), "both logins ended");
      require(story(proxy, "Quitter").equals(List.of("PlayerSetupEvent", "PlayerDisconnectEvent")),
          "a client gone during setup is decided on no further, got " + story(proxy, "Quitter"));
      require(story(proxy, "Leaver").equals(List.of("PlayerSetupEvent", "PlayerLoginEvent", "PlayerDisconnectEvent")),
          "nor one gone during PlayerLoginEvent, got " + story(proxy, "Leaver"));
      require(ended(proxy, "Quitter") == LoginStatus.CANCELLED_BY_USER && ended(proxy, "Leaver") == LoginStatus.CANCELLED_BY_USER,
          "both cancelled by the user");

      try (Client kicked = Client.open(proxy.port(), "Kicked")) {
        byte[] reply = kicked.readDirect();
        require(id(reply) == 0 && text(reply).contains("Not today"), "a login listener's kick is a login disconnect, got " + text(reply));
      }
      require(waitFor(() -> ended(proxy, "Kicked") != null, 10_000), "the kicked login ended");
      require(ended(proxy, "Kicked") == LoginStatus.CANCELLED_BY_PROXY, "cancelled by the proxy");
      Thread.sleep(200);
      require(proxy.recorder.of(PlayerDisconnectEvent.class).size() == 3, "one ending each, got " + proxy.recorder.names());
      require(lobby.logins.get() == 0 && proxy.recorder.of(PlayerPostLoginEvent.class).isEmpty(), "none of them reached a backend");
    }
  }

  /** The shutdown's sweep of online players could not see a login a plugin was still holding. */
  private static void aShutdownDuringALoginRefusesIt() throws Exception {
    try (Backend lobby = new Backend("lobby"); Proxy proxy = new Proxy(lobby, maintenance(false))) {
      CountDownLatch holding = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      proxy.recorder.hook = event -> {
        if (event instanceof PlayerSetupEvent) {
          holding.countDown();
          try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
      };
      try (Client late = Client.open(proxy.port(), "Late")) {
        require(holding.await(10, TimeUnit.SECONDS), "held in setup");
        proxy.runtime.shutdown();
        require(waitFor(proxy.runtime::shuttingDown, 10_000), "shutting down");
        release.countDown();
        byte[] reply = late.readDirect();
        require(id(reply) == 0 && text(reply).contains("shutting down"), "refused with the shutdown message, got " + text(reply));
      }
      require(waitFor(() -> ended(proxy, "Late") != null, 10_000), "the login ended");
      require(ended(proxy, "Late") == LoginStatus.CANCELLED_BY_PROXY && lobby.logins.get() == 0, "by the proxy, before any backend");
    }
  }

  // --- the proxy's own refusals ------------------------------------------------------------------

  private static void securityRefusalsComeBeforeAnyPluginSeesThePlayer() throws Exception {
    VersionGateSettings gate = new VersionGateSettings(true, Set.of(765), OptionalInt.empty(), OptionalInt.empty(), null, "Wrong version", null, false);
    try (Backend lobby = new Backend("lobby"); Proxy proxy = new Proxy(lobby, maintenance(false), gate, AuthenticationSettings.offline(), null)) {
      grantEverything(proxy);
      try (Client old = Client.open(proxy.port(), "Old")) {
        byte[] reply = old.readDirect();
        require(id(reply) == 0 && text(reply).contains("Wrong version"), "the version gate refuses at login, got " + text(reply));
      }
      try (Socket socket = new Socket("127.0.0.1", proxy.port())) {
        socket.setSoTimeout(10_000);
        MinecraftFrames.write(socket.getOutputStream(), new Handshake(3, "localhost", 25565, 2).encode());
        byte[] reply = MinecraftFrames.read(socket.getInputStream(), 1 << 16);
        require(text(reply).contains("Unsupported"), "an unsupported protocol is refused, got " + text(reply));
      }
      try (Socket socket = new Socket("127.0.0.1", proxy.port())) {
        socket.setSoTimeout(10_000);
        MinecraftFrames.write(socket.getOutputStream(), new byte[] {0x00, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
        require(socket.getInputStream().read() < 0, "a malformed handshake is closed");
      } catch (java.net.SocketException reset) { }
      Thread.sleep(200);
      require(playerEvents(proxy).isEmpty(), "no plugin heard of any of them, got " + proxy.recorder.names());
    }

    // Online mode: a failed authentication is the proxy's alone to decide.
    PlayerAuthenticator refusing = new PlayerAuthenticator() {
      @Override public AuthenticationMode mode() { return AuthenticationMode.ONLINE; }
      @Override public PlayerProfile verify(gg.tame.conduit.auth.SessionQuery query) throws AuthenticationException {
        throw new AuthenticationException("not a paid account");
      }
    };
    AuthenticationSettings online = new AuthenticationSettings(AuthenticationMode.ONLINE, "http://127.0.0.1:1/unused", 1000);
    try (Backend lobby = new Backend("lobby"); Proxy proxy = new Proxy(lobby, maintenance(false), null, online, refusing)) {
      grantEverything(proxy);
      try (Socket socket = new Socket("127.0.0.1", proxy.port())) {
        socket.setSoTimeout(10_000);
        PacketTransport encrypted = encrypt(socket, "Pirate");
        byte[] reply = encrypted.read(1 << 16);
        require(id(reply) == 0 && text(reply).contains("Failed to verify"), "refused at authentication, got " + text(reply));
      }
      Thread.sleep(200);
      require(playerEvents(proxy).isEmpty() && lobby.logins.get() == 0, "no plugin heard of the player, got " + proxy.recorder.names());
    }
  }

  /** A plugin provider that grants every node and claims every player: none of it reaches the proxy's own refusals. */
  private static void grantEverything(Proxy proxy) {
    proxy.runtime.setPermissionProvider(proxy.owner, (subject, node) -> true);
  }

  /** Handshake, Login Start and the encryption exchange of a 1.8 client, as far as the proxy's verdict. */
  static PacketTransport encrypt(Socket socket, String name) throws Exception {
    MinecraftFrames.write(socket.getOutputStream(), new Handshake(47, "localhost", 25565, 2).encode());
    MinecraftFrames.write(socket.getOutputStream(), loginStart(name));
    var request = gg.tame.conduit.login.EncryptionRequest.decode(P47, MinecraftFrames.read(socket.getInputStream(), 4096));
    byte[] shared = new byte[16];
    new java.security.SecureRandom().nextBytes(shared);
    var key = java.security.KeyFactory.getInstance("RSA").generatePublic(new java.security.spec.X509EncodedKeySpec(request.publicKey()));
    MinecraftFrames.write(socket.getOutputStream(), packet(1, output -> {
      MinecraftOutput.bytes(output, gg.tame.conduit.crypto.RsaKeys.encrypt(key, shared));
      MinecraftOutput.bytes(output, gg.tame.conduit.crypto.RsaKeys.encrypt(key, request.verifyToken()));
    }));
    return new PacketTransport(
        gg.tame.conduit.crypto.CipherStreams.decrypting(socket.getInputStream(), gg.tame.conduit.crypto.AesCfb8.decryptor(shared)),
        gg.tame.conduit.crypto.CipherStreams.encrypting(socket.getOutputStream(), gg.tame.conduit.crypto.AesCfb8.encryptor(shared)));
  }

  // --- slash-named commands --------------------------------------------------------------------

  /**
   * "//lpv" used to run "lpv": both the session and the dispatcher took a slash off. Velocity takes
   * one and looks the rest up exactly, and LuckPerms registers an alias "/lpv" to be found that way.
   */
  private static void aDoubleSlashReachesASlashNamedCommandOnly() throws Exception {
    try (Backend lobby = new Backend("lobby"); Proxy proxy = new Proxy(lobby, maintenance(false))) {
      List<String> ran = Collections.synchronizedList(new ArrayList<>());
      var commands = proxy.runtime.commands();
      commands.register(proxy.owner, Command.builder("/wand").handler((source, arguments) -> ran.add("/wand " + arguments)).build());
      commands.register(proxy.owner, Command.builder("wand").handler((source, arguments) -> ran.add("wand " + arguments)).build());
      commands.register(proxy.owner, Command.builder("solo").handler((source, arguments) -> ran.add("solo " + arguments)).build());
      boolean refused = false;
      try { commands.register(proxy.owner, Command.builder("//").build()); } catch (IllegalArgumentException expected) { refused = true; }
      require(refused, "a name of slashes alone could never be typed, and is refused");
      require(commands.hasCommand("/wand") && commands.hasCommand("wand"), "both names are held");

      byte[] tree = CommandGraphs.proxyOnly(ProtocolDefinition.forVersion(765), List.of("lobby"), proxy.runtime.commandManager().names());
      byte[] literal = "/wand".getBytes(StandardCharsets.UTF_8);
      require(indexOf(tree, literal) >= 0, "the command tree a 1.13+ client parses against declares /wand, so it accepts //wand");

      try (Client client = Client.join(proxy.port(), "Builder")) {
        client.send(chat("//wand here"));
        client.send(chat("/wand there"));
        client.send(chat("//solo"));
        require(waitFor(() -> ran.size() >= 2, 10_000), "both commands ran, got " + ran);
        require(lobby.await(packet -> chatText(packet).equals("//solo")), "//solo is nobody's here, and goes to the backend as typed");
        require(ran.equals(List.of("/wand [here]", "wand [there]")), "//wand ran /wand and /wand ran wand, never solo, got " + ran);
      }
      require(commands.execute(proxy.runtime.console(), "//wand console") && ran.getLast().equals("/wand [console]"),
          "the API takes one optional slash off the same way");
    }
  }

  private static int indexOf(byte[] haystack, byte[] needle) {
    outer:
    for (int start = 0; start <= haystack.length - needle.length; start++) {
      for (int index = 0; index < needle.length; index++) if (haystack[start + index] != needle[index]) continue outer;
      return start;
    }
    return -1;
  }

  // --- the Velocity adapter --------------------------------------------------------------------

  /**
   * Shaped like LuckPerms on Velocity: it loads each user in PermissionsSetupEvent (asynchronously, as
   * from a database), answers through the PermissionFunction it installs there, keeps them until
   * DisconnectEvent, and registers "/lpx" beside "lpx" so that "//lpx" works.
   */
  private static final String LPX = """
      package lpx;
      import com.velocitypowered.api.command.SimpleCommand;
      import com.velocitypowered.api.event.EventTask;
      import com.velocitypowered.api.event.ResultedEvent;
      import com.velocitypowered.api.event.Subscribe;
      import com.velocitypowered.api.event.connection.DisconnectEvent;
      import com.velocitypowered.api.event.connection.LoginEvent;
      import com.velocitypowered.api.event.permission.PermissionsSetupEvent;
      import com.velocitypowered.api.event.player.ServerPreConnectEvent;
      import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
      import com.velocitypowered.api.permission.Tristate;
      import com.velocitypowered.api.plugin.Plugin;
      import com.velocitypowered.api.proxy.Player;
      import com.velocitypowered.api.proxy.ProxyServer;
      import java.util.Map;
      import java.util.UUID;
      import java.util.concurrent.ConcurrentHashMap;
      import java.util.concurrent.CountDownLatch;
      import java.util.concurrent.TimeUnit;
      import javax.inject.Inject;
      import net.kyori.adventure.text.Component;

      @Plugin(id = "lpx", name = "LPX", version = "1")
      public final class Lpx {
        @SuppressWarnings("unchecked")
        static void signal(String value) { ((java.util.Queue<String>) System.getProperties().get("login.test.signals")).add(value); }
        /** The plugin's storage, by name: a node's value, anything else undefined. */
        private static final Map<String, Map<String, Tristate>> STORAGE = Map.of(
            "Staffer", Map.of("conduit.maintenance.bypass", Tristate.TRUE),
            "Chief", Map.of("conduit.admin", Tristate.TRUE),
            "Denied", Map.of("conduit.maintenance.bypass", Tristate.FALSE),
            "Listed", Map.of("conduit.maintenance.bypass", Tristate.FALSE),
            "Banned", Map.of("conduit.maintenance.bypass", Tristate.TRUE),
            "Homeless", Map.of("conduit.maintenance.bypass", Tristate.TRUE),
            "Midway", Map.of("conduit.maintenance.bypass", Tristate.TRUE));
        private final Map<UUID, Map<String, Tristate>> loaded = new ConcurrentHashMap<>();
        private final ProxyServer proxy;
        @Inject public Lpx(ProxyServer proxy) {
          this.proxy = proxy;
          System.getProperties().put("login.test.proxy", proxy);
        }
        @Subscribe public void init(ProxyInitializeEvent event) {
          var commands = proxy.getCommandManager();
          commands.register(commands.metaBuilder("lpx").aliases("/lpx").plugin(this).build(), (SimpleCommand) invocation ->
              signal("cmd:" + invocation.alias() + ":" + String.join(" ", invocation.arguments())));
        }
        @Subscribe public EventTask setup(PermissionsSetupEvent event) {
          if (!(event.getSubject() instanceof Player player)) return null;
          return EventTask.async(() -> {
            String name = player.getUsername();
            @SuppressWarnings("unchecked")
            CountDownLatch hold = ((Map<String, CountDownLatch>) System.getProperties().get("login.test.latches")).get(name);
            if (hold != null) {
              signal("waiting:" + name);
              try { hold.await(8, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            UUID id = player.getUniqueId();
            loaded.put(id, STORAGE.getOrDefault(name, Map.of()));
            event.setProvider(subject -> permission -> {
              if (name.equals("Broken")) throw new IllegalStateException("storage down");
              Map<String, Tristate> user = loaded.get(id);
              return user == null ? Tristate.UNDEFINED : user.getOrDefault(permission, Tristate.UNDEFINED);
            });
          });
        }
        @Subscribe public void login(LoginEvent event) {
          signal("login:" + event.getPlayer().getUsername());
          if (event.getPlayer().getUsername().equals("Banned")) event.setResult(ResultedEvent.ComponentResult.denied(Component.text("banned")));
        }
        @Subscribe public void connect(ServerPreConnectEvent event) {
          if (event.getPlayer().getUsername().equals("Homeless")) event.setResult(ServerPreConnectEvent.ServerResult.denied());
        }
        @Subscribe public void gone(DisconnectEvent event) {
          loaded.remove(event.getPlayer().getUniqueId());
          signal("disconnect:" + event.getPlayer().getUsername() + ":" + event.getLoginStatus() + ":" + loaded.size());
        }
      }
      """;

  private static final Queue<String> SIGNALS = new ConcurrentLinkedQueue<>();

  private static void aVelocityPermissionPluginDecidesTheBypassAndIsReleased() throws Exception {
    SIGNALS.clear();
    Map<String, CountDownLatch> latches = new ConcurrentHashMap<>();
    System.getProperties().put("login.test.signals", SIGNALS);
    System.getProperties().put("login.test.latches", latches);
    Path root = TempFiles.dir("login-velocity");
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    VelocityCompatTests.jar(VelocityCompatTests.compile(root, "lpx.Lpx", LPX, List.of(), true), plugins.resolve("Lpx.jar"), null);
    try (Backend lobby = new Backend("lobby");
         Proxy proxy = new Proxy(lobby, maintenance(true, "Listed"), null, AuthenticationSettings.offline(), null, plugins)) {
      require(waitFor(() -> proxy.runtime.plugins().plugin("lpx").isPresent() && proxy.runtime.commands().hasCommand("lpx"), 10_000), "lpx enabled");
      require(proxy.runtime.commands().hasCommand("/lpx"), "the slash alias is registered");

      // Let through by the plugin's own permissions, set up before maintenance asked.
      try (Client staffer = Client.join(proxy.port(), "Staffer")) {
        staffer.send(chat("//lpx info"));
        staffer.send(chat("/lpx info"));
        awaitSignal("cmd:/lpx:info");
        awaitSignal("cmd:lpx:info");
        try (Client chief = Client.join(proxy.port(), "Chief")) { awaitSignal("login:Chief"); }
        awaitSignal("disconnect:Chief:SUCCESSFUL_LOGIN:1");

        // Refused: FALSE, UNDEFINED, a function that throws. The allowlist lets a name in whatever the plugin says.
        refusedByMaintenance(proxy, "Denied");
        refusedByMaintenance(proxy, "Nobody");
        refusedByMaintenance(proxy, "Broken");
        awaitSignal("disconnect:Denied:CANCELLED_BY_PROXY:1");
        awaitSignal("disconnect:Nobody:CANCELLED_BY_PROXY:1");
        awaitSignal("disconnect:Broken:CANCELLED_BY_PROXY:1");
        require(!SIGNALS.contains("login:Denied") && !SIGNALS.contains("login:Nobody"), "maintenance refuses before LoginEvent: " + SIGNALS);
        try (Client listed = Client.join(proxy.port(), "Listed")) { awaitSignal("login:Listed"); }
        awaitSignal("disconnect:Listed:SUCCESSFUL_LOGIN:1");

        // Every other way a login can end still reaches DisconnectEvent, once.
        try (Client banned = Client.open(proxy.port(), "Banned")) {
          require(text(banned.readDirect()).contains("banned"), "LoginEvent denied Banned");
        }
        awaitSignal("disconnect:Banned:CANCELLED_BY_PROXY:1");
        try (Client homeless = Client.open(proxy.port(), "Homeless")) {
          require(id(homeless.readDirect()) == 0, "no server would take Homeless");
        }
        awaitSignal("disconnect:Homeless:PRE_SERVER_JOIN:1");
        latches.put("Slowpoke", new CountDownLatch(1));
        Client slowpoke = Client.open(proxy.port(), "Slowpoke");
        awaitSignal("waiting:Slowpoke");
        slowpoke.close();
        Thread.sleep(200);
        latches.get("Slowpoke").countDown();
        awaitSignal("disconnect:Slowpoke:CANCELLED_BY_USER_BEFORE_COMPLETE:1");
        require(!SIGNALS.contains("login:Slowpoke"), "a client gone during permission setup is decided on no further: " + SIGNALS);
      }
      awaitSignal("disconnect:Staffer:SUCCESSFUL_LOGIN:0");
      for (String name : List.of("Staffer", "Chief", "Denied", "Nobody", "Broken", "Listed", "Banned", "Homeless", "Slowpoke")) {
        require(SIGNALS.stream().filter(line -> line.startsWith("disconnect:" + name + ":")).count() == 1, "one DisconnectEvent for " + name + ": " + SIGNALS);
      }
      require(waitFor(() -> adapterHolds("players") == 0 && adapterHolds("grants") == 0, 10_000),
          "the adapter let go of every player, refused ones included");
      require(lobby.logins.get() == 3, "Staffer, Chief and Listed reached the backend, nobody else");

      // The plugin is disabled while a player's permissions are being set up: its function is not
      // kept, and nothing of it is left answering for Conduit.
      latches.put("Midway", new CountDownLatch(1));
      try (Client midway = Client.open(proxy.port(), "Midway")) {
        awaitSignal("waiting:Midway");
        proxy.runtime.plugins().disable(proxy.runtime.plugins().plugin("lpx").orElseThrow());
        latches.get("Midway").countDown();
        byte[] reply = midway.readDirect();
        require(id(reply) == 0 && text(reply).contains(KICK), "refused: the plugin that would have let Midway in is gone, got " + text(reply));
      }
      require(waitFor(() -> ended(proxy, "Midway") != null, 10_000), "Midway's login ended");
      require(proxy.runtime.permissions() instanceof gg.tame.conduit.permission.DefaultPermissionProvider,
          "Conduit's default is back, not a provider installed for a plugin that had gone, got " + proxy.runtime.permissions());
      require(waitFor(() -> adapterHolds("players") == 0 && adapterHolds("grants") == 0, 10_000), "and the adapter holds nothing for Midway");
    }
  }

  /** White-box, as VelocityCompatTests' "/vtest cached": how many players one of the adapter's maps holds. */
  private static int adapterHolds(String map) throws ReflectiveOperationException {
    Object proxy = System.getProperties().get("login.test.proxy");
    Object environment = field(proxy, "environment");
    Object owner = map.equals("grants") ? field(environment, "permissions") : environment;
    return ((Map<?, ?>) field(owner, map)).size();
  }
  private static Object field(Object owner, String name) throws ReflectiveOperationException {
    java.lang.reflect.Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }

  private static void awaitSignal(String expected) throws InterruptedException {
    require(waitFor(() -> SIGNALS.contains(expected), 10_000), "never saw " + expected + " in " + SIGNALS);
  }

  // --- one player, one session -------------------------------------------------------------------

  private static AuthenticationSettings kickExisting() {
    return new AuthenticationSettings(AuthenticationMode.OFFLINE, AuthenticationSettings.DEFAULT_SESSION_URL, 5_000, true);
  }

  /** A second login of a connected player was let in beside the first, and only one of them was indexed. */
  private static void aSecondLoginOfAConnectedPlayerIsRefused() throws Exception {
    try (Backend lobby = new Backend("lobby"); Proxy proxy = new Proxy(lobby, maintenance(false))) {
      try (Client first = Client.join(proxy.port(), "Twin")) {
        require(proxy.recorder.await(PlayerPostLoginEvent.class, 1), "the first login joined");
        // Offline mode derives the UUID from the name as typed, so TWIN is another UUID, but one name.
        for (String name : List.of("Twin", "TWIN")) {
          try (Client second = Client.open(proxy.port(), name)) {
            byte[] reply = second.readDirect();
            require(id(reply) == 0 && text(reply).contains("already connected"), name + " is refused at login, got " + text(reply));
            require(second.ends(), "and its connection ends");
          }
        }
        Thread.sleep(200);
        require(proxy.recorder.of(PlayerSetupEvent.class).size() == 1, "no plugin heard of the refused logins, got " + proxy.recorder.names());
        require(lobby.logins.get() == 1, "nor did a backend");
        require(proxy.runtime.player("Twin").orElseThrow() == proxy.recorder.of(PlayerSetupEvent.class).getFirst().player(),
            "the first session is still the one online");
        first.send(chat("still here"));
        require(lobby.await(packet -> chatText(packet).equals("still here")), "and still plays");
      }
      require(proxy.recorder.await(PlayerDisconnectEvent.class, 1), "the first leaves");
      try (Client again = Client.join(proxy.port(), "Twin")) {
        require(proxy.recorder.await(PlayerPostLoginEvent.class, 2), "once it has gone, the same player joins again");
      }
    }
  }

  /** Online mode: the account is the UUID, so a renamed account's second login is the same player. */
  private static void anOnlineAccountIsOneSessionWhateverItsName() throws Exception {
    PlayerAuthenticator oneAccount = new PlayerAuthenticator() {
      @Override public AuthenticationMode mode() { return AuthenticationMode.ONLINE; }
      @Override public PlayerProfile verify(gg.tame.conduit.auth.SessionQuery query) {
        return new PlayerProfile(new java.util.UUID(9, 9), query.username(), List.of(), true);
      }
    };
    AuthenticationSettings online = new AuthenticationSettings(AuthenticationMode.ONLINE, "http://127.0.0.1:1/unused", 1000);
    try (Backend lobby = new Backend("lobby"); Proxy proxy = new Proxy(lobby, maintenance(false), null, online, oneAccount);
         Socket first = new Socket("127.0.0.1", proxy.port()); Socket renamed = new Socket("127.0.0.1", proxy.port())) {
      first.setSoTimeout(10_000);
      renamed.setSoTimeout(10_000);
      require(id(encrypt(first, "OldName").read(1 << 16)) == 2, "the account joins");
      require(proxy.recorder.await(PlayerPostLoginEvent.class, 1), "joined");
      byte[] reply = encrypt(renamed, "NewName").read(1 << 16);
      require(id(reply) == 0 && text(reply).contains("already connected"), "the same account under a new name is refused, got " + text(reply));
      Thread.sleep(200);
      require(proxy.recorder.of(PlayerSetupEvent.class).size() == 1, "before any plugin heard of it");
    }
  }

  /**
   * A player who quits and rejoins at once finds their last session still ending. They wait for it,
   * rather than being refused over it, and plugins hear it end before they hear of the new one.
   */
  private static void aRejoinWaitsForTheLastSessionToEnd() throws Exception {
    try (Backend lobby = new Backend("lobby"); Proxy proxy = new Proxy(lobby, maintenance(false))) {
      CountDownLatch leaving = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      proxy.recorder.hook = event -> {
        if (!(event instanceof PlayerDisconnectEvent)) return;
        leaving.countDown();
        try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
      };
      Client first = Client.join(proxy.port(), "Rejoiner");
      require(proxy.recorder.await(PlayerPostLoginEvent.class, 1), "joined");
      first.close();
      require(leaving.await(10, TimeUnit.SECONDS), "the first session is ending, a disconnect listener still running");
      try (Client second = Client.open(proxy.port(), "Rejoiner")) {
        Thread.sleep(300);
        require(proxy.recorder.of(PlayerSetupEvent.class).size() == 1, "the rejoin waits: neither refused nor set up yet");
        proxy.recorder.hook = event -> { };
        release.countDown();
        byte[] success = second.readDirect();
        require(id(success) == 2, "then it is let in, got " + text(success));
        require(proxy.recorder.await(PlayerPostLoginEvent.class, 2), "and joins");
        require(proxy.recorder.names(PlayerSetupEvent.class, PlayerDisconnectEvent.class)
                .equals(List.of("PlayerSetupEvent", "PlayerDisconnectEvent", "PlayerSetupEvent")),
            "the last session's end reached plugins before the new one's setup, got " + proxy.recorder.names());
      }
    }
  }

  /** Several logins of one player at the same instant: exactly one gets in, the rest are refused. */
  private static void loginsAtTheSameInstantLetOneIn() throws Exception {
    try (Backend lobby = new Backend("lobby"); Proxy proxy = new Proxy(lobby, maintenance(false))) {
      int attempts = 6;
      CountDownLatch go = new CountDownLatch(1);
      List<String> outcomes = Collections.synchronizedList(new ArrayList<>());
      List<Client> clients = Collections.synchronizedList(new ArrayList<>());
      List<Thread> racers = new ArrayList<>();
      for (int i = 0; i < attempts; i++) {
        racers.add(Thread.ofPlatform().daemon().start(() -> {
          try {
            go.await();
            Client client = Client.open(proxy.port(), "Racer");
            clients.add(client);
            byte[] reply = client.readDirect();
            outcomes.add(id(reply) == 2 ? "in" : text(reply));
          } catch (Exception failed) { outcomes.add("failed: " + failed); }
        }));
      }
      go.countDown();
      for (Thread racer : racers) racer.join(20_000);
      try {
        require(outcomes.size() == attempts && outcomes.stream().filter("in"::equals).count() == 1
            && outcomes.stream().filter(outcome -> !outcome.equals("in")).allMatch(outcome -> outcome.contains("already connected")),
            "one login in, every other refused, got " + outcomes);
        require(waitFor(() -> proxy.recorder.of(PlayerSetupEvent.class).size() == 1, 5_000) && lobby.logins.get() == 1,
            "only the one that got in was set up and reached a backend, got " + proxy.recorder.names());
      } finally {
        for (Client client : clients) client.close();
      }
    }
  }

  private static void kickExistingPlayersHandsTheSessionToTheNewLogin() throws Exception {
    try (Backend lobby = new Backend("lobby"); Proxy proxy = new Proxy(lobby, maintenance(false), null, kickExisting(), null)) {
      try (Client first = Client.join(proxy.port(), "Mover")) {
        require(proxy.recorder.await(PlayerPostLoginEvent.class, 1), "the first login joined");
        try (Client second = Client.join(proxy.port(), "Mover")) {
          require(first.await(packet -> id(packet) == DISCONNECT_OUT && text(packet).contains("logged in from another location")),
              "the session in the way is kicked");
          require(first.ends(), "and ends");
          require(proxy.recorder.await(PlayerPostLoginEvent.class, 2), "the new login joins");
          require(proxy.runtime.player("Mover").orElseThrow() == proxy.recorder.of(PlayerSetupEvent.class).getLast().player(),
              "and is the one online");
          require(proxy.recorder.names(PlayerSetupEvent.class, PlayerDisconnectEvent.class)
                  .equals(List.of("PlayerSetupEvent", "PlayerDisconnectEvent", "PlayerSetupEvent")),
              "plugins heard the old session end before the new one was set up, got " + proxy.recorder.names());
          require(proxy.recorder.of(PlayerDisconnectEvent.class).getFirst().loginStatus() == LoginStatus.SUCCESSFUL_LOGIN,
              "a session that had joined leaves as a completed login");
        }
      }

      // Displaced while its own login is still being decided: that login ends as a conflicting one.
      CountDownLatch holding = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      java.util.concurrent.atomic.AtomicBoolean heldOnce = new java.util.concurrent.atomic.AtomicBoolean();
      proxy.recorder.hook = event -> {
        if (event instanceof PlayerLoginEvent login && login.player().username().equals("Slow") && heldOnce.compareAndSet(false, true)) {
          holding.countDown();
          try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
      };
      // Both sessions above have been closed, but a session ends on whichever thread finds it over,
      // so the count is only settled once their disconnects have arrived. Wait for them: a snapshot
      // taken before they land makes the check below count one of them as the displaced login.
      require(waitFor(() -> proxy.recorder.of(PlayerDisconnectEvent.class).size()
              == proxy.recorder.of(PlayerSetupEvent.class).size(), 10_000),
          "both earlier sessions ended, got " + proxy.recorder.names());
      int ended = proxy.recorder.of(PlayerDisconnectEvent.class).size();
      try (Client slow = Client.open(proxy.port(), "Slow")) {
        require(holding.await(10, TimeUnit.SECONDS), "the first login is held in PlayerLoginEvent");
        try (Client fast = Client.open(proxy.port(), "Slow")) {
          byte[] kicked = slow.readDirect();
          require(id(kicked) == 0 && text(kicked).contains("logged in from another location"), "the held login is kicked at login, got " + text(kicked));
          release.countDown();
          require(id(fast.readDirect()) == 2, "the new login gets in once the old one has ended");
          require(waitFor(() -> proxy.recorder.of(PlayerDisconnectEvent.class).size() == ended + 1, 10_000), "the displaced login ended");
          PlayerDisconnectEvent displaced = proxy.recorder.of(PlayerDisconnectEvent.class).getLast();
          require(displaced.loginStatus() == LoginStatus.CONFLICTING_LOGIN && !displaced.completedLogin(),
              "as a conflicting login, got " + displaced.loginStatus());
          require(proxy.recorder.await(PlayerPostLoginEvent.class, 3), "and the new one joined");
        }
      }
    }
  }

  /** Velocity's DisconnectEvent says CONFLICTING_LOGIN for a login a newer one displaced. */
  private static void aVelocityPluginHearsADisplacedLoginAsConflicting() throws Exception {
    SIGNALS.clear();
    Map<String, CountDownLatch> latches = new ConcurrentHashMap<>();
    System.getProperties().put("login.test.signals", SIGNALS);
    System.getProperties().put("login.test.latches", latches);
    Path root = TempFiles.dir("login-conflict-velocity");
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    VelocityCompatTests.jar(VelocityCompatTests.compile(root, "lpx.Lpx", LPX, List.of(), true), plugins.resolve("Lpx.jar"), null);
    try (Backend lobby = new Backend("lobby");
         Proxy proxy = new Proxy(lobby, maintenance(false), null, kickExisting(), null, plugins)) {
      require(waitFor(() -> proxy.runtime.plugins().plugin("lpx").isPresent(), 10_000), "lpx enabled");
      latches.put("Double", new CountDownLatch(1));
      try (Client older = Client.open(proxy.port(), "Double")) {
        awaitSignal("waiting:Double");
        try (Client newer = Client.open(proxy.port(), "Double")) {
          require(text(older.readDirect()).contains("logged in from another location"), "the older login is kicked");
          latches.get("Double").countDown();
          require(id(newer.readDirect()) == 2, "the newer one gets in");
          awaitSignal("login:Double");
          require(SIGNALS.contains("disconnect:Double:CONFLICTING_LOGIN:0"), "the displaced login's DisconnectEvent: " + SIGNALS);
          require(List.copyOf(SIGNALS).indexOf("disconnect:Double:CONFLICTING_LOGIN:0") < List.copyOf(SIGNALS).indexOf("login:Double"),
              "before the newer login's LoginEvent, and the older one never got one: " + SIGNALS);
          require(SIGNALS.stream().filter("login:Double"::equals).count() == 1, "one LoginEvent: " + SIGNALS);
        }
      }
      awaitSignal("disconnect:Double:SUCCESSFUL_LOGIN:0");
      require(waitFor(() -> adapterHolds("players") == 0 && adapterHolds("grants") == 0, 10_000), "the adapter let go of both");
    }
  }

  // --- harness ---------------------------------------------------------------------------------

  /** A native permission plugin: loads each player's nodes in PlayerSetupEvent, drops them at PlayerDisconnectEvent. */
  public static final class TablePermissions implements PermissionProvider {
    private final Map<String, Set<String>> table;
    final Map<Player, Set<String>> loaded = new ConcurrentHashMap<>();
    final List<String> asked = Collections.synchronizedList(new ArrayList<>());
    TablePermissions(Map<String, Set<String>> table) { this.table = table; }
    @Subscribe public void setUp(PlayerSetupEvent event) { loaded.put(event.player(), table.getOrDefault(event.player().username(), Set.of())); }
    @Subscribe public void gone(PlayerDisconnectEvent event) { loaded.remove(event.player()); }
    @Override public boolean hasPermission(PermissionSubject subject, String permission) {
      Set<String> nodes = subject instanceof Player player ? loaded.get(player) : null;
      if (subject instanceof Player player) asked.add(player.username() + ":" + permission + ":" + (nodes == null ? "unknown" : "loaded"));
      return nodes != null && nodes.contains(permission);
    }
  }

  private static void refusedByMaintenance(Proxy proxy, String name) throws Exception {
    try (Client client = Client.open(proxy.port(), name)) {
      byte[] reply = client.readDirect();
      require(id(reply) == 0 && text(reply).contains(KICK), name + " gets the maintenance kick at login, got " + text(reply));
      require(client.ends(), "and the connection ends");
    }
    require(waitFor(() -> ended(proxy, name) != null, 10_000), name + "'s login ended");
  }

  /** Every player event that names {@code username}, by type, in order. */
  private static List<String> story(Proxy proxy, String username) {
    return proxy.recorder.of(Event.class).stream().filter(event -> username.equals(username(event)))
        .map(event -> event.getClass().getSimpleName()).toList();
  }
  private static List<Event> playerEvents(Proxy proxy) {
    return proxy.recorder.of(Event.class).stream().filter(event -> username(event) != null).toList();
  }
  private static String username(Event event) {
    Player player = switch (event) {
      case PlayerSetupEvent setup -> setup.player();
      case PlayerLoginEvent login -> login.player();
      case PlayerAuthenticatedEvent authenticated -> authenticated.player();
      case PlayerPostLoginEvent joined -> joined.player();
      case PlayerDisconnectEvent left -> left.player();
      default -> null;
    };
    return player == null ? null : player.username();
  }
  /** How {@code username}'s login ended, or null while it has not; throws if it ended twice. */
  private static LoginStatus ended(Proxy proxy, String username) {
    List<LoginStatus> endings = proxy.recorder.of(PlayerDisconnectEvent.class).stream()
        .filter(event -> event.player().username().equals(username)).map(PlayerDisconnectEvent::loginStatus).toList();
    require(endings.size() <= 1, username + " was reported gone more than once: " + endings);
    return endings.isEmpty() ? null : endings.getFirst();
  }

  private interface Action { void run() throws Exception; }
  /** What {@code body} wrote to standard error, which it still reaches; ConduitLog writes errors there. */
  private static String capturingErr(Action body) throws Exception {
    PrintStream original = System.err;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    OutputStream tee = new OutputStream() {
      @Override public void write(int value) { original.write(value); synchronized (captured) { captured.write(value); } }
      @Override public void write(byte[] bytes, int offset, int length) {
        original.write(bytes, offset, length);
        synchronized (captured) { captured.write(bytes, offset, length); }
      }
    };
    System.setErr(new PrintStream(tee, true, StandardCharsets.UTF_8));
    try { body.run(); }
    finally { System.setErr(original); }
    synchronized (captured) { return captured.toString(StandardCharsets.UTF_8); }
  }

  /** A proxy with one backend, the given maintenance settings, and a recorder on every event. */
  private static final class Proxy implements AutoCloseable {
    final MinecraftProxy proxy;
    final ConduitRuntime runtime;
    final Recorder recorder = new Recorder();
    final Plugin owner = new TestPlugin("login-probe");
    private final Thread serving;
    Proxy(Backend backend, MaintenanceSettings maintenance) throws Exception {
      this(backend, maintenance, null, AuthenticationSettings.offline(), null);
    }
    Proxy(Backend backend, MaintenanceSettings maintenance, VersionGateSettings versions, AuthenticationSettings auth,
          PlayerAuthenticator authenticator) throws Exception {
      this(backend, maintenance, versions, auth, authenticator, TempFiles.dir("login-lifecycle").resolve("plugins"));
    }
    Proxy(Backend backend, MaintenanceSettings maintenance, VersionGateSettings versions, AuthenticationSettings auth,
          PlayerAuthenticator authenticator, Path plugins) throws Exception {
      OpsSettings ops = new OpsSettings(OpsSettings.CURRENT_SCHEMA, maintenance, new HealthSettings(false, 10_000, 1_500, 3, 2),
          versions, null, null, null, null, null);
      String name = backend.server().name();
      ConduitConfiguration configuration = new ConduitConfiguration(new InetSocketAddress("127.0.0.1", reservePort()), 1 << 20,
          ForwardingMode.NONE, Optional.empty(), List.of(backend.server()), List.of(name), List.of(name), auth, Optional.empty(), ops);
      proxy = new MinecraftProxy(configuration,
          authenticator != null ? authenticator : gg.tame.conduit.auth.Authenticators.create(auth),
          gg.tame.conduit.crypto.RsaKeys.generate(), plugins);
      runtime = proxy.runtime();
      runtime.events().register(owner, recorder);
      serving = Thread.ofPlatform().daemon().name("login-test-serve").start(() -> {
        try { proxy.serve(); } catch (Exception ignored) { }
      });
    }
    int port() throws IOException { return proxy.port(); }
    @Override public void close() throws Exception {
      proxy.close();
      serving.join(10_000);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
