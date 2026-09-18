// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.NativeApiTests.id;
import static gg.tame.conduit.tests.NativeApiTests.loginStart;
import static gg.tame.conduit.tests.NativeApiTests.packet;
import static gg.tame.conduit.tests.NativeApiTests.reservePort;
import static gg.tame.conduit.tests.NativeApiTests.text;
import static gg.tame.conduit.tests.NativeApiTests.waitFor;

import gg.tame.conduit.api.event.player.GameProfileRequestEvent;
import gg.tame.conduit.api.event.player.PlayerAuthenticatedEvent;
import gg.tame.conduit.api.event.player.PlayerDisconnectEvent;
import gg.tame.conduit.api.event.player.PlayerPostLoginEvent;
import gg.tame.conduit.api.event.player.PlayerPreLoginEvent;
import gg.tame.conduit.api.event.player.PlayerPreLoginEvent.Authentication;
import gg.tame.conduit.api.event.player.PlayerSetupEvent;
import gg.tame.conduit.api.event.player.PlayerTransferEvent;
import gg.tame.conduit.api.player.GameProfile;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.api.plugin.Plugin;
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
import gg.tame.conduit.login.LoginStart;
import gg.tame.conduit.login.PlayerProfile;
import gg.tame.conduit.login.ProfileProperty;
import gg.tame.conduit.network.MinecraftProxy;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.runtime.ConduitRuntime;
import gg.tame.conduit.tests.NativeApiTests.Backend;
import gg.tame.conduit.tests.NativeApiTests.Client;
import gg.tame.conduit.tests.NativeApiTests.Recorder;
import gg.tame.conduit.tests.NativeApiTests.TestPlugin;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What plugins get to say about a login before it is a player, and what reaches the backends and
 * the client afterwards: PlayerPreLoginEvent, game profiles and GameProfileRequestEvent, and 1.20.5+
 * transfers, natively and through compiled Velocity plugins. Scripted clients and backends.
 */
public final class LoginFlowTests {
  public static void main(String[] a) throws Exception { run(); }

  public static void run() throws Exception {
    aPreLoginDenialComesBeforeEncryptionAndReachesNoOneElse();
    aPreLoginCanForceOfflineModeOnAnOnlineProxy();
    aPreLoginCanForceOnlineModeOnAnOfflineProxy();
    velocityPreLoginResultsMapOntoTheNativeEvent();
    aPlayersProfileCarriesTheSessionServersProperties();
    aReplacedProfileIsWhatBackendsAndTheClientSee();
    velocityGameProfilesMapBothWays();
    aTransferredClientLogsInAsAnyOther();
    transfersGoThroughTheEventWhoeverAsks();
    velocityTransfersAndHandshakeIntentMap();
    System.out.println("LoginFlowTests OK");
  }

  // --- transfers -----------------------------------------------------------------------------------

  static final gg.tame.conduit.protocol.ProtocolDefinition P766 = gg.tame.conduit.protocol.ProtocolDefinition.forVersion(766);

  private static void aTransferredClientLogsInAsAnyOther() throws Exception {
    try (Backend766 lobby = new Backend766("lobby");
         Proxy proxy = new Proxy(List.of(lobby.server()), AuthenticationSettings.offline(), null, null, ForwardingMode.NONE, Optional.empty())) {
      try (Client766 arrived = Client766.open(proxy.port(), "Arrived", Handshake.TRANSFER); Client766 plain = Client766.open(proxy.port(), "Plain", 2)) {
        arrived.awaitState(gg.tame.conduit.protocol.ConnectionState.PLAY);
        plain.awaitState(gg.tame.conduit.protocol.ConnectionState.PLAY);
        require(waitFor(() -> proxy.runtime.player("Arrived").isPresent() && proxy.runtime.player("Plain").isPresent(), 10_000), "both joined");
        require(proxy.runtime.player("Arrived").orElseThrow().transferred() && !proxy.runtime.player("Plain").orElseThrow().transferred(),
            "the player says how it arrived");
        require(proxy.recorder.of(PlayerPreLoginEvent.class).stream().filter(PlayerPreLoginEvent::transferred).map(PlayerPreLoginEvent::username)
            .toList().equals(List.of("Arrived")), "and so does its PlayerPreLoginEvent");
        require(lobby.intents.equals(List.of(2, 2)), "the backend is asked for an ordinary login either way: " + lobby.intents);
      }
      // A client too old to have transfers cannot arrive by one: that intent is malformed, as it was.
      try (Socket socket = new Socket("127.0.0.1", proxy.port())) {
        socket.setSoTimeout(10_000);
        MinecraftFrames.write(socket.getOutputStream(), new Handshake(765, "localhost", 25565, Handshake.TRANSFER).encode());
        MinecraftFrames.write(socket.getOutputStream(), loginStart("Old"));
        require(socket.getInputStream().read() < 0, "closed without a word");
      } catch (java.net.SocketException reset) { }
    }
  }

  private static void transfersGoThroughTheEventWhoeverAsks() throws Exception {
    try (Backend766 lobby = new Backend766("lobby");
         Proxy proxy = new Proxy(List.of(lobby.server()), AuthenticationSettings.offline(), null, null, ForwardingMode.NONE, Optional.empty())) {
      proxy.recorder.hook = event -> {
        if (!(event instanceof PlayerTransferEvent transfer)) return;
        if (transfer.host().startsWith("blocked")) transfer.setCancelled(true);
        if (transfer.host().equals("old.example")) transfer.setTarget("new.example", 25580);
      };
      try (Client766 client = Client766.open(proxy.port(), "Hopper", 2)) {
        client.awaitState(gg.tame.conduit.protocol.ConnectionState.PLAY);
        require(waitFor(() -> proxy.runtime.player("Hopper").map(player -> player.connectionState().equals("PLAY")).orElse(false), 10_000), "playing");
        Player player = proxy.runtime.player("Hopper").orElseThrow();
        require(player.transferToHost("lobby2.example", 25570), "sent");
        require(client.awaitTransfer().equals("lobby2.example:25570"), "the client is told where to go");
        require(!player.transferToHost("blocked.example", 25571), "a cancelled transfer sends nothing and says so");
        require(player.transferToHost("old.example", 25572), "a changed one is sent");
        require(client.awaitTransfer().equals("new.example:25580"), "to where the listener pointed it");
        // The backend's own Transfer goes through the same event.
        lobby.transfer("Hopper", "backend.example", 25590);
        require(client.awaitTransfer().equals("backend.example:25590"), "a backend's transfer reaches the client");
        lobby.transfer("Hopper", "blocked-by-plugin.example", 25591);
        lobby.transfer("Hopper", "after.example", 25592);
        require(client.awaitTransfer().equals("after.example:25592"), "a cancelled backend transfer is dropped, the next one is not");
        List<PlayerTransferEvent> seen = proxy.recorder.of(PlayerTransferEvent.class);
        require(seen.stream().map(event -> event.fromBackend() + ":" + event.cancelled()).toList()
                .equals(List.of("false:false", "false:true", "false:false", "true:false", "true:true", "true:false")),
            "every transfer was an event, saying who asked: " + seen.stream().map(event -> event.host() + "/" + event.fromBackend()).toList());
        boolean refused = false;
        try { player.transferToHost("x.example", 0); } catch (IllegalArgumentException expected) { refused = true; }
        require(refused, "a port of 0 is refused");
      }
    }
    // A client without a Transfer packet gets none.
    try (Backend lobby = new Backend("lobby"); Proxy proxy = new Proxy(lobby, AuthenticationSettings.offline(), null, null)) {
      try (Client old = Client.join(proxy.port(), "Old")) {
        require(proxy.recorder.await(PlayerPostLoginEvent.class, 1), "joined");
        require(!proxy.runtime.player("Old").orElseThrow().transferToHost("new.example", 25565), "a 1.8 client cannot be transferred");
        require(proxy.recorder.of(PlayerTransferEvent.class).isEmpty(), "and nobody was asked about it");
      }
    }
  }

  private static final String TRANSFERV = """
      package transferv;
      import com.velocitypowered.api.command.SimpleCommand;
      import com.velocitypowered.api.event.Subscribe;
      import com.velocitypowered.api.event.connection.PostLoginEvent;
      import com.velocitypowered.api.event.connection.PreTransferEvent;
      import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
      import com.velocitypowered.api.plugin.Plugin;
      import com.velocitypowered.api.proxy.ProxyServer;
      import java.net.InetSocketAddress;
      import javax.inject.Inject;

      @Plugin(id = "transferv", name = "TransferV", version = "1")
      public final class TransferV {
        @SuppressWarnings("unchecked")
        static void signal(String value) { ((java.util.Queue<String>) System.getProperties().get("flow.test.signals")).add(value); }
        private final ProxyServer proxy;
        @Inject public TransferV(ProxyServer proxy) { this.proxy = proxy; }
        @Subscribe public void init(ProxyInitializeEvent event) {
          proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("hop").plugin(this).build(), (SimpleCommand) invocation -> {
            String[] args = invocation.arguments();
            proxy.getPlayer(args[0]).orElseThrow().transferToHost(InetSocketAddress.createUnresolved(args[1], 25599));
            signal("hopped:" + args[1]);
          });
        }
        @Subscribe public void joined(PostLoginEvent event) {
          signal("intent:" + event.getPlayer().getUsername() + ":" + event.getPlayer().getHandshakeIntent());
        }
        @Subscribe public void transfer(PreTransferEvent event) {
          InetSocketAddress to = event.originalAddress();
          signal("pretransfer:" + event.player().getUsername() + ":" + to.getHostString() + ":" + to.getPort());
          if (to.getHostString().equals("denied.example")) event.setResult(PreTransferEvent.TransferResult.denied());
          if (to.getHostString().equals("moved.example")) {
            event.setResult(PreTransferEvent.TransferResult.transferTo(InetSocketAddress.createUnresolved("rewritten.example", 25600)));
          }
        }
      }
      """;

  private static void velocityTransfersAndHandshakeIntentMap() throws Exception {
    Queue<String> signals = new ConcurrentLinkedQueue<>();
    System.getProperties().put("flow.test.signals", signals);
    Path plugins = compiledPlugin("transferv.TransferV", TRANSFERV);
    try (Backend766 lobby = new Backend766("lobby");
         Proxy proxy = new Proxy(List.of(lobby.server()), AuthenticationSettings.offline(), null, plugins, ForwardingMode.NONE, Optional.empty())) {
      require(waitFor(() -> proxy.runtime.plugins().plugin("transferv").isPresent(), 10_000), "transferv enabled");
      try (Client766 arrived = Client766.open(proxy.port(), "VHopper", Handshake.TRANSFER); Client766 plain = Client766.open(proxy.port(), "VPlain", 2)) {
        arrived.awaitState(gg.tame.conduit.protocol.ConnectionState.PLAY);
        plain.awaitState(gg.tame.conduit.protocol.ConnectionState.PLAY);
        require(waitFor(() -> signals.contains("intent:VHopper:TRANSFER") && signals.contains("intent:VPlain:LOGIN"), 10_000),
            "getHandshakeIntent says how each arrived: " + signals);
        require(waitFor(() -> proxy.runtime.player("VHopper").map(player -> player.connectionState().equals("PLAY")).orElse(false), 10_000), "playing");
        proxy.runtime.commands().execute(proxy.runtime.console(), "hop VHopper moved.example");
        require(arrived.awaitTransfer().equals("rewritten.example:25600"), "PreTransferEvent's address is where the client goes");
        proxy.runtime.commands().execute(proxy.runtime.console(), "hop VHopper denied.example");
        require(waitFor(() -> signals.contains("hopped:denied.example"), 10_000), "the plugin asked");
        lobby.transfer("VHopper", "backend.example", 25601);
        require(arrived.awaitTransfer().equals("backend.example:25601"), "a denied transfer sent nothing; the backend's next one arrives");
        require(signals.contains("pretransfer:VHopper:moved.example:25599") && signals.contains("pretransfer:VHopper:denied.example:25599")
            && signals.contains("pretransfer:VHopper:backend.example:25601"), "PreTransferEvent for the plugin's and the backend's: " + signals);
      }
    }
  }

  /**
   * A 1.20.5 server: logs the player in, configures them at once, and then sits in Play, sending a
   * Transfer when told to. It records the handshake intent it was asked for.
   */
  static final class Backend766 implements AutoCloseable {
    private final String name;
    private final java.net.ServerSocket listener = new java.net.ServerSocket(0);
    final List<Integer> intents = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final List<Socket> sockets = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final java.util.Map<String, Socket> playing = new java.util.concurrent.ConcurrentHashMap<>();
    Backend766(String name) throws IOException {
      this.name = name;
      Thread.ofPlatform().daemon().name("flow-backend766-" + name).start(() -> {
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
        var in = socket.getInputStream();
        Handshake handshake = Handshake.decode(MinecraftFrames.read(in, 4096));
        if (handshake.nextState() == 1) return;
        intents.add(handshake.nextState());
        var start = new java.io.DataInputStream(new java.io.ByteArrayInputStream(MinecraftFrames.read(in, 4096)));
        gg.tame.conduit.protocol.MinecraftInput.varInt(start);
        String player = gg.tame.conduit.protocol.MinecraftInput.string(start, 16);
        UUID uuid = new UUID(start.readLong(), start.readLong());
        write(socket, packet(p766(gg.tame.conduit.protocol.ConnectionState.LOGIN, gg.tame.conduit.protocol.PacketKind.LOGIN_SUCCESS), output -> {
          output.writeLong(uuid.getMostSignificantBits()); output.writeLong(uuid.getLeastSignificantBits());
          MinecraftOutput.string(output, player); MinecraftOutput.varInt(output, 0); output.writeBoolean(false);
        }));
        int loginAck = P766.id(gg.tame.conduit.protocol.ConnectionState.LOGIN, gg.tame.conduit.protocol.PacketDirection.CLIENT_TO_SERVER,
            gg.tame.conduit.protocol.PacketKind.LOGIN_ACKNOWLEDGED);
        while (MinecraftFrames.read(in, 1 << 16)[0] != loginAck) { }
        int finish = p766(gg.tame.conduit.protocol.ConnectionState.CONFIGURATION, gg.tame.conduit.protocol.PacketKind.CONFIGURATION_FINISH);
        write(socket, new byte[] {(byte) finish});
        int finished = P766.id(gg.tame.conduit.protocol.ConnectionState.CONFIGURATION, gg.tame.conduit.protocol.PacketDirection.CLIENT_TO_SERVER,
            gg.tame.conduit.protocol.PacketKind.CONFIGURATION_FINISH);
        while (MinecraftFrames.read(in, 1 << 16)[0] != finished) { }
        playing.put(player, socket);
        while (true) MinecraftFrames.read(in, 1 << 20);
      } catch (Exception ended) { }
    }
    /** A Transfer packet, as a 1.20.5 server's /transfer sends one. */
    void transfer(String player, String host, int port) throws Exception {
      require(waitFor(() -> playing.containsKey(player), 10_000), player + " is on " + name);
      write(playing.get(player), packet(p766(gg.tame.conduit.protocol.ConnectionState.PLAY, gg.tame.conduit.protocol.PacketKind.PLAY_TRANSFER), output -> {
        MinecraftOutput.string(output, host); MinecraftOutput.varInt(output, port);
      }));
    }
    private static void write(Socket socket, byte[] packet) throws IOException {
      synchronized (socket) { MinecraftFrames.write(socket.getOutputStream(), packet); }
    }
    @Override public void close() throws IOException {
      listener.close();
      synchronized (sockets) { for (Socket socket : sockets) socket.close(); }
    }
  }

  private static int p766(gg.tame.conduit.protocol.ConnectionState state, gg.tame.conduit.protocol.PacketKind kind) {
    return P766.id(state, gg.tame.conduit.protocol.PacketDirection.SERVER_TO_CLIENT, kind);
  }

  /**
   * A 1.20.5 client that keeps up as the real one does -- acknowledges Login Success, answers Known
   * Packs with none, finishes configuration when told, re-enters it on Start Configuration -- and keeps
   * every Transfer it is sent.
   */
  static final class Client766 implements AutoCloseable {
    private final Socket socket;
    private final java.util.concurrent.LinkedBlockingQueue<String> transfers = new java.util.concurrent.LinkedBlockingQueue<>();
    private volatile gg.tame.conduit.protocol.ConnectionState state = gg.tame.conduit.protocol.ConnectionState.LOGIN;
    private Client766(Socket socket) { this.socket = socket; }
    static Client766 open(int port, String name, int intent) throws IOException {
      Socket socket = new Socket("127.0.0.1", port);
      socket.setSoTimeout(20_000);
      Client766 client = new Client766(socket);
      MinecraftFrames.write(socket.getOutputStream(), new Handshake(766, "localhost", 25565, intent).encode());
      UUID uuid = LoginStart.offlineUuid(name);
      MinecraftFrames.write(socket.getOutputStream(), packet(0, output -> {
        MinecraftOutput.string(output, name); output.writeLong(uuid.getMostSignificantBits()); output.writeLong(uuid.getLeastSignificantBits());
      }));
      Thread.ofPlatform().daemon().name("flow-client766-" + name).start(client::read);
      return client;
    }
    private void read() {
      var c2s = gg.tame.conduit.protocol.PacketDirection.CLIENT_TO_SERVER;
      var s2c = gg.tame.conduit.protocol.PacketDirection.SERVER_TO_CLIENT;
      var login = gg.tame.conduit.protocol.ConnectionState.LOGIN;
      var configuration = gg.tame.conduit.protocol.ConnectionState.CONFIGURATION;
      var play = gg.tame.conduit.protocol.ConnectionState.PLAY;
      try {
        while (true) {
          byte[] frame = MinecraftFrames.read(socket.getInputStream(), 1 << 21);
          var at = state;
          int packetId = id(frame);
          if (at == login && P766.is(login, s2c, packetId, gg.tame.conduit.protocol.PacketKind.LOGIN_SUCCESS)) {
            state = configuration;
            send(new byte[] {(byte) P766.id(login, c2s, gg.tame.conduit.protocol.PacketKind.LOGIN_ACKNOWLEDGED)});
          } else if (at == configuration && P766.is(configuration, s2c, packetId, gg.tame.conduit.protocol.PacketKind.CONFIGURATION_KNOWN_PACKS)) {
            send(new byte[] {(byte) P766.id(configuration, c2s, gg.tame.conduit.protocol.PacketKind.CONFIGURATION_KNOWN_PACKS), 0});
          } else if (at == configuration && P766.is(configuration, s2c, packetId, gg.tame.conduit.protocol.PacketKind.CONFIGURATION_FINISH)) {
            state = play;
            send(new byte[] {(byte) P766.id(configuration, c2s, gg.tame.conduit.protocol.PacketKind.CONFIGURATION_FINISH)});
          } else if (at == play && P766.is(play, s2c, packetId, gg.tame.conduit.protocol.PacketKind.PLAY_START_CONFIGURATION)) {
            state = configuration;
            send(new byte[] {(byte) P766.id(play, c2s, gg.tame.conduit.protocol.PacketKind.PLAY_CONFIGURATION_ACKNOWLEDGED)});
          } else if (P766.is(at, s2c, packetId, at == play ? gg.tame.conduit.protocol.PacketKind.PLAY_TRANSFER
              : gg.tame.conduit.protocol.PacketKind.CONFIGURATION_TRANSFER)) {
            var input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(frame));
            gg.tame.conduit.protocol.MinecraftInput.varInt(input);
            transfers.add(gg.tame.conduit.protocol.MinecraftInput.string(input, 32767) + ":" + gg.tame.conduit.protocol.MinecraftInput.varInt(input));
          }
        }
      } catch (IOException closed) { }
    }
    private synchronized void send(byte[] packet) throws IOException { MinecraftFrames.write(socket.getOutputStream(), packet); }
    void awaitState(gg.tame.conduit.protocol.ConnectionState wanted) throws InterruptedException {
      require(waitFor(() -> state == wanted, 15_000), "the client never reached " + wanted + ", it is in " + state);
    }
    /** The next Transfer's host:port. */
    String awaitTransfer() throws InterruptedException {
      String next = transfers.poll(10, java.util.concurrent.TimeUnit.SECONDS);
      require(next != null, "the client was sent no Transfer");
      return next;
    }
    @Override public void close() throws IOException { socket.close(); }
  }

  // --- game profiles -------------------------------------------------------------------------------

  private static void aPlayersProfileCarriesTheSessionServersProperties() throws Exception {
    CountingAuthenticator sessions = new CountingAuthenticator();
    try (Backend lobby = new Backend("lobby"); Proxy proxy = new Proxy(lobby, ONLINE, sessions, null)) {
      proxy.recorder.hook = event -> {
        if (event instanceof PlayerPreLoginEvent pre && pre.username().equals("Offline")) pre.setAuthentication(Authentication.FORCE_OFFLINE);
      };
      try (Socket socket = new Socket("127.0.0.1", proxy.port()); Client offline = Client.join(proxy.port(), "Offline")) {
        socket.setSoTimeout(10_000);
        require(id(LoginLifecycleTests.encrypt(socket, "Skinned").read(1 << 16)) == 2, "joined");
        require(proxy.recorder.await(PlayerPostLoginEvent.class, 2), "both joined");
        GameProfile skinned = proxy.runtime.player("Skinned").orElseThrow().gameProfile();
        require(skinned.properties().equals(List.of(new GameProfile.Property("textures", "c2tpbg==", Optional.of("c2ln"))))
            && skinned.name().equals("Skinned"), "the session server's textures, value and signature, got " + skinned);
        require(proxy.runtime.player("Offline").orElseThrow().gameProfile().properties().isEmpty(), "an offline player has none");
        require(proxy.recorder.names(PlayerPreLoginEvent.class, GameProfileRequestEvent.class, PlayerSetupEvent.class).subList(0, 3)
            .equals(List.of("PlayerPreLoginEvent", "GameProfileRequestEvent", "PlayerSetupEvent")),
            "pre-login, then the profile, then setup, got " + proxy.recorder.names());
        GameProfileRequestEvent request = proxy.recorder.of(GameProfileRequestEvent.class).stream()
            .filter(event -> event.username().equals("Skinned")).findFirst().orElseThrow();
        require(request.onlineMode() && request.originalProfile().equals(skinned) && request.gameProfile().equals(skinned),
            "the event offers the authenticated profile");
        require(!proxy.recorder.of(GameProfileRequestEvent.class).stream()
            .filter(event -> event.username().equals("Offline")).findFirst().orElseThrow().onlineMode(), "and says an offline one is not");
      }
    }
  }

  private static final UUID REPLACED = new UUID(0xAB, 0xCD);

  /**
   * A plugin's profile is what modern forwarding tells the backend, what the client's own tab-list
   * entry shows, and what the player is; the account that logged in still counts as connected.
   */
  private static void aReplacedProfileIsWhatBackendsAndTheClientSee() throws Exception {
    Path secret = TempFiles.file("login-flow-forwarding", ".secret");
    Files.writeString(secret, "flow-secret");
    try (ForwardingBackend lobby = new ForwardingBackend("lobby", true);
         Proxy proxy = new Proxy(List.of(lobby.server()), AuthenticationSettings.offline(), null, null, ForwardingMode.MODERN, Optional.of(secret))) {
      java.util.concurrent.atomic.AtomicBoolean replacedOnce = new java.util.concurrent.atomic.AtomicBoolean();
      proxy.recorder.hook = event -> {
        if (!(event instanceof GameProfileRequestEvent request)) return;
        if (request.username().equals("Skinless") && replacedOnce.compareAndSet(false, true)) {
          request.setGameProfile(new GameProfile(REPLACED, "Skinned", List.of(new GameProfile.Property("textures", "bmV3", Optional.of("c2lnMg==")))));
        }
        if (request.username().equals("Other")) request.setGameProfile(new GameProfile(REPLACED, "Other2", List.of()));
      };
      try (ServerKickTests.Client765 client = ServerKickTests.Client765.open(proxy.port(), "Skinless")) {
        byte[] self = client.await(gg.tame.conduit.protocol.ConnectionState.PLAY, gg.tame.conduit.protocol.PacketKind.PLAY_PLAYER_INFO_UPDATE,
            "the client's own tab-list entry");
        require(contains(self, uuidBytes(REPLACED)) && contains(self, "Skinned".getBytes(StandardCharsets.UTF_8))
            && contains(self, "bmV3".getBytes(StandardCharsets.UTF_8)), "the entry is the replaced profile, skin included");
        require(lobby.forwarded.equals(List.of(REPLACED + ":Skinned:textures=bmV3/c2lnMg==")), "modern forwarding told the backend the replacement: " + lobby.forwarded);
        Player player = proxy.runtime.player("Skinned").orElseThrow();
        require(player.uniqueId().equals(REPLACED) && player.gameProfile().properties().size() == 1 && !player.authenticated(),
            "the player is the replacement, and still an offline player");
        // The same account again, not replaced this time: refused, as the account is connected.
        try (Client again = Client.open(proxy.port(), "Skinless")) {
          require(text(again.readDirect()).contains("already connected"), "a second login of the account that logged in is refused");
        }
        // Another account replaced into the connected player's UUID: refused too.
        try (Client other = Client.open(proxy.port(), "Other")) {
          require(text(other.readDirect()).contains("already connected"), "a replacement that collides with a connected player is refused");
        }
      }
    }
    // Forwarding none: the backend's own Login Start carries the replacement.
    try (ForwardingBackend lobby = new ForwardingBackend("lobby", false);
         Proxy proxy = new Proxy(List.of(lobby.server()), AuthenticationSettings.offline(), null, null, ForwardingMode.NONE, Optional.empty())) {
      proxy.recorder.hook = event -> {
        if (event instanceof GameProfileRequestEvent request) request.setGameProfile(new GameProfile(REPLACED, "Renamed", List.of()));
      };
      try (ServerKickTests.Client765 client = ServerKickTests.Client765.open(proxy.port(), "Original")) {
        client.await(gg.tame.conduit.protocol.ConnectionState.PLAY, gg.tame.conduit.protocol.PacketKind.PLAY_LOGIN, "Join Game");
        require(lobby.loginStarts.equals(List.of("Renamed:" + REPLACED)), "the backend was asked to log in the replacement: " + lobby.loginStarts);
      }
    }
  }

  private static final String PROFILEV = """
      package profilev;
      import com.velocitypowered.api.event.Subscribe;
      import com.velocitypowered.api.event.connection.PostLoginEvent;
      import com.velocitypowered.api.event.player.GameProfileRequestEvent;
      import com.velocitypowered.api.plugin.Plugin;
      import com.velocitypowered.api.util.GameProfile;
      import java.util.List;

      @Plugin(id = "profilev", name = "ProfileV", version = "1")
      public final class ProfileV {
        @SuppressWarnings("unchecked")
        static void signal(String value) { ((java.util.Queue<String>) System.getProperties().get("flow.test.signals")).add(value); }
        @Subscribe public void request(GameProfileRequestEvent event) {
          GameProfile original = event.getOriginalProfile();
          signal("request:" + event.getUsername() + ":" + event.isOnlineMode() + ":" + original.getName() + ":" + original.getProperties().size()
              + ":" + event.getConnection().getProtocolVersion().getProtocol());
          if (event.getUsername().equals("VSkin")) {
            event.setGameProfile(original.withName("VSkinned").withProperties(List.of(new GameProfile.Property("textures", "dmVs", "c2ln3"))));
          }
          if (event.getUsername().equals("VBad")) event.setGameProfile(original.withName("no spaces allowed"));
        }
        @Subscribe public void joined(PostLoginEvent event) {
          var player = event.getPlayer();
          GameProfile profile = player.getGameProfile();
          signal("profile:" + player.getUsername() + ":" + profile.getName() + ":" + profile.getId().equals(player.getUniqueId()) + ":"
              + profile.getProperties().stream().map(p -> p.getName() + "=" + p.getValue() + "/" + p.getSignature()).toList()
              + ":" + player.getGameProfileProperties().size());
        }
      }
      """;

  private static void velocityGameProfilesMapBothWays() throws Exception {
    Queue<String> signals = new ConcurrentLinkedQueue<>();
    System.getProperties().put("flow.test.signals", signals);
    Path plugins = compiledPlugin("profilev.ProfileV", PROFILEV);
    try (Backend lobby = new Backend("lobby"); Proxy proxy = new Proxy(lobby, ONLINE, new CountingAuthenticator(), plugins)) {
      require(waitFor(() -> proxy.runtime.plugins().plugin("profilev").isPresent(), 10_000), "profilev enabled");
      for (String name : List.of("VSkin", "VBad")) {
        try (Socket socket = new Socket("127.0.0.1", proxy.port())) {
          socket.setSoTimeout(10_000);
          require(id(LoginLifecycleTests.encrypt(socket, name).read(1 << 16)) == 2, name + " joined");
          require(waitFor(() -> signals.stream().anyMatch(signal -> signal.startsWith("profile:")
              && signal.contains(name.equals("VSkin") ? "VSkinned" : "VBad")), 10_000), "PostLoginEvent for " + name + ": " + signals);
        }
      }
      require(signals.contains("request:VSkin:true:VSkin:1:47"), "the original profile, as the session server gave it: " + signals);
      require(signals.contains("profile:VSkinned:VSkinned:true:[textures=dmVs/c2ln3]:1"), "the plugin's profile is the player's: " + signals);
      require(signals.contains("profile:VBad:VBad:true:[textures=c2tpbg==/c2ln]:1"), "an unusable name is refused, and the profile stays: " + signals);
    }
  }

  private static byte[] uuidBytes(UUID uuid) {
    return java.nio.ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
  }
  private static boolean contains(byte[] haystack, byte[] needle) {
    outer:
    for (int start = 0; start <= haystack.length - needle.length; start++) {
      for (int index = 0; index < needle.length; index++) if (haystack[start + index] != needle[index]) continue outer;
      return true;
    }
    return false;
  }

  /**
   * A 1.20.4 server that records how it is asked to log a player in: with {@code modern}, the profile
   * in modern forwarding's answer; without, the name and UUID in the Login Start. Then it lets them in.
   */
  static final class ForwardingBackend implements AutoCloseable {
    private final String name;
    private final boolean modern;
    private final java.net.ServerSocket listener = new java.net.ServerSocket(0);
    final List<String> forwarded = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    final List<String> loginStarts = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final List<Socket> sockets = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    ForwardingBackend(String name, boolean modern) throws IOException {
      this.name = name; this.modern = modern;
      Thread.ofPlatform().daemon().name("flow-backend-" + name).start(() -> {
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
        var in = socket.getInputStream();
        var out = socket.getOutputStream();
        if (Handshake.decode(MinecraftFrames.read(in, 4096)).nextState() != 2) return;
        var start = new java.io.DataInputStream(new java.io.ByteArrayInputStream(MinecraftFrames.read(in, 4096)));
        gg.tame.conduit.protocol.MinecraftInput.varInt(start);
        String player = gg.tame.conduit.protocol.MinecraftInput.string(start, 16);
        UUID uuid = new UUID(start.readLong(), start.readLong());
        loginStarts.add(player + ":" + uuid);
        if (modern) {
          MinecraftFrames.write(out, packet(0x04, output -> {
            MinecraftOutput.varInt(output, 7); MinecraftOutput.string(output, "velocity:player_info");
          }));
          var answer = new java.io.DataInputStream(new java.io.ByteArrayInputStream(MinecraftFrames.read(in, 1 << 16)));
          gg.tame.conduit.protocol.MinecraftInput.varInt(answer);
          gg.tame.conduit.protocol.MinecraftInput.varInt(answer);
          answer.readBoolean();
          answer.readNBytes(32);
          gg.tame.conduit.protocol.MinecraftInput.varInt(answer);
          gg.tame.conduit.protocol.MinecraftInput.string(answer, 255);
          uuid = new UUID(answer.readLong(), answer.readLong());
          player = gg.tame.conduit.protocol.MinecraftInput.string(answer, 16);
          StringBuilder record = new StringBuilder(uuid + ":" + player);
          int properties = gg.tame.conduit.protocol.MinecraftInput.varInt(answer);
          for (int i = 0; i < properties; i++) {
            record.append(':').append(gg.tame.conduit.protocol.MinecraftInput.string(answer, 64)).append('=')
                .append(gg.tame.conduit.protocol.MinecraftInput.string(answer, 32767));
            if (answer.readBoolean()) record.append('/').append(gg.tame.conduit.protocol.MinecraftInput.string(answer, 1024));
          }
          forwarded.add(record.toString());
        }
        UUID loggedIn = uuid;
        String loggedInName = player;
        MinecraftFrames.write(out, packet(0x02, output -> {
          output.writeLong(loggedIn.getMostSignificantBits()); output.writeLong(loggedIn.getLeastSignificantBits());
          MinecraftOutput.string(output, loggedInName); MinecraftOutput.varInt(output, 0);
        }));
        while (MinecraftFrames.read(in, 1 << 16)[0] != 0x03) { }
        MinecraftFrames.write(out, new byte[] {0x02});
        while (MinecraftFrames.read(in, 1 << 16)[0] != 0x02) { }
        MinecraftFrames.write(out, DirectLoginCompressionTests.joinGame765());
        while (true) MinecraftFrames.read(in, 1 << 20);
      } catch (Exception ended) { }
    }
    @Override public void close() throws IOException {
      listener.close();
      synchronized (sockets) { for (Socket socket : sockets) socket.close(); }
    }
  }

  // --- PlayerPreLoginEvent -------------------------------------------------------------------------

  private static void aPreLoginDenialComesBeforeEncryptionAndReachesNoOneElse() throws Exception {
    CountingAuthenticator sessions = new CountingAuthenticator();
    try (Backend lobby = new Backend("lobby"); Proxy proxy = new Proxy(lobby, ONLINE, sessions, null)) {
      proxy.recorder.hook = event -> {
        if (event instanceof PlayerPreLoginEvent pre && pre.username().startsWith("Banned")) pre.deny(Text.of("You are banned").color(TextColor.RED));
      };
      try (Socket socket = new Socket("127.0.0.1", proxy.port())) {
        socket.setSoTimeout(10_000);
        MinecraftFrames.write(socket.getOutputStream(), new Handshake(47, "play.example.net\0FML3\0", 25577, 2).encode());
        MinecraftFrames.write(socket.getOutputStream(), loginStart("Banned"));
        byte[] reply = MinecraftFrames.read(socket.getInputStream(), 1 << 16);
        require(id(reply) == 0 && text(reply).contains("You are banned") && text(reply).contains("red"),
            "a Login Disconnect with the reason, not an Encryption Request, got id " + id(reply) + " " + text(reply));
      }
      // A 1.20.4 client's Login Start carries a UUID: the event names it, unverified.
      UUID claimed = new UUID(1, 2);
      try (Socket socket = new Socket("127.0.0.1", proxy.port())) {
        socket.setSoTimeout(10_000);
        MinecraftFrames.write(socket.getOutputStream(), new Handshake(765, "localhost", 25565, 2).encode());
        MinecraftFrames.write(socket.getOutputStream(), packet(0, output -> {
          MinecraftOutput.string(output, "Banned765"); output.writeLong(1); output.writeLong(2);
        }));
        require(id(MinecraftFrames.read(socket.getInputStream(), 1 << 16)) == 0, "refused too");
      }
      Thread.sleep(200);
      require(sessions.verified.get() == 0, "no session-server round trip for a refused login");
      List<PlayerPreLoginEvent> seen = proxy.recorder.of(PlayerPreLoginEvent.class);
      require(seen.size() == 2, "one PlayerPreLoginEvent each, got " + proxy.recorder.names());
      PlayerPreLoginEvent legacy = seen.getFirst();
      require(legacy.username().equals("Banned") && legacy.claimedUniqueId().isEmpty() && legacy.protocolVersion() == 47
          && legacy.remoteAddress().isLoopbackAddress() && !legacy.transferred()
          && legacy.virtualHost().getHostString().equals("play.example.net") && legacy.virtualHost().getPort() == 25577,
          "what the 1.8 client claimed, Forge markers removed: " + legacy.virtualHost());
      require(seen.getLast().claimedUniqueId().equals(Optional.of(claimed)) && seen.getLast().protocolVersion() == 765,
          "the 1.20.4 client's own UUID");
      require(proxy.recorder.of(PlayerSetupEvent.class).isEmpty() && proxy.recorder.of(PlayerDisconnectEvent.class).isEmpty()
          && lobby.logins.get() == 0, "no player was made, so there is nothing to set up or end, and no backend: " + proxy.recorder.names());
    }
  }

  private static void aPreLoginCanForceOfflineModeOnAnOnlineProxy() throws Exception {
    CountingAuthenticator sessions = new CountingAuthenticator();
    try (Backend lobby = new Backend("lobby"); Proxy proxy = new Proxy(lobby, ONLINE, sessions, null)) {
      proxy.recorder.hook = event -> {
        if (event instanceof PlayerPreLoginEvent pre && pre.username().equals("Cracked")) pre.setAuthentication(Authentication.FORCE_OFFLINE);
      };
      try (Client cracked = Client.join(proxy.port(), "Cracked")) {
        require(proxy.recorder.await(PlayerPostLoginEvent.class, 1), "joined without encrypting");
        Player player = proxy.runtime.player("Cracked").orElseThrow();
        require(!player.authenticated() && player.uniqueId().equals(LoginStart.offlineUuid("Cracked")),
            "an offline player: unverified, with the name's UUID, got " + player.uniqueId());
        require(sessions.verified.get() == 0 && proxy.recorder.of(PlayerAuthenticatedEvent.class).isEmpty(), "never checked with the session server");
      }
      // The proxy's own mode still holds for everyone else.
      try (Socket socket = new Socket("127.0.0.1", proxy.port())) {
        socket.setSoTimeout(10_000);
        require(id(LoginLifecycleTests.encrypt(socket, "Premium").read(1 << 16)) == 2, "an ordinary login encrypts and is checked");
        require(proxy.recorder.await(PlayerPostLoginEvent.class, 2), "joined");
        require(sessions.verified.get() == 1 && proxy.runtime.player("Premium").orElseThrow().authenticated(), "and is authenticated");
      }
    }
  }

  private static void aPreLoginCanForceOnlineModeOnAnOfflineProxy() throws Exception {
    com.sun.net.httpserver.HttpServer sessionServer = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger asked = new AtomicInteger();
    sessionServer.createContext("/hasJoined", exchange -> {
      asked.incrementAndGet();
      byte[] body = exchange.getRequestURI().getRawQuery().contains("username=Imposter")
          ? new byte[0]
          : "{\"id\":\"00000000000000000000000000000042\",\"name\":\"Premium\",\"properties\":[{\"name\":\"textures\",\"value\":\"c2tpbg==\",\"signature\":\"c2ln\"}]}"
              .getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(body.length == 0 ? 204 : 200, body.length == 0 ? -1 : body.length);
      if (body.length > 0) exchange.getResponseBody().write(body);
      exchange.close();
    });
    sessionServer.start();
    try {
      AuthenticationSettings offline = new AuthenticationSettings(AuthenticationMode.OFFLINE,
          "http://127.0.0.1:" + sessionServer.getAddress().getPort() + "/hasJoined", 2_000);
      try (Backend lobby = new Backend("lobby"); Proxy proxy = new Proxy(lobby, offline, null, null)) {
        proxy.recorder.hook = event -> {
          if (event instanceof PlayerPreLoginEvent pre && !pre.username().equals("Plain")) pre.setAuthentication(Authentication.FORCE_ONLINE);
        };
        try (Socket socket = new Socket("127.0.0.1", proxy.port())) {
          socket.setSoTimeout(10_000);
          require(id(LoginLifecycleTests.encrypt(socket, "Premium").read(1 << 16)) == 2, "encrypted, checked, let in");
          require(proxy.recorder.await(PlayerPostLoginEvent.class, 1), "joined");
          Player player = proxy.runtime.player("Premium").orElseThrow();
          require(player.authenticated() && player.uniqueId().equals(new UUID(0, 0x42)), "with the session server's identity, got " + player.uniqueId());
          require(proxy.recorder.of(PlayerAuthenticatedEvent.class).size() == 1, "and PlayerAuthenticatedEvent");
        }
        try (Socket socket = new Socket("127.0.0.1", proxy.port())) {
          socket.setSoTimeout(10_000);
          byte[] reply = LoginLifecycleTests.encrypt(socket, "Imposter").read(1 << 16);
          require(id(reply) == 0 && text(reply).contains("Failed to verify"), "a failed check refuses the login, got " + text(reply));
        }
        try (Client plain = Client.join(proxy.port(), "Plain")) {
          require(proxy.recorder.await(PlayerPostLoginEvent.class, 2), "everyone else is still offline, unchecked");
        }
        require(asked.get() == 2, "the session server was asked twice, got " + asked.get());
      }
      // An offline proxy's session URL was never held to online mode's rule; forced online, it is.
      AuthenticationSettings insecure = new AuthenticationSettings(AuthenticationMode.OFFLINE, "http://192.0.2.1/hasJoined", 2_000);
      try (Backend lobby = new Backend("lobby"); Proxy proxy = new Proxy(lobby, insecure, null, null)) {
        proxy.recorder.hook = event -> { if (event instanceof PlayerPreLoginEvent pre) pre.setAuthentication(Authentication.FORCE_ONLINE); };
        try (Client refused = Client.open(proxy.port(), "Premium")) {
          byte[] reply = refused.readDirect();
          require(id(reply) == 0 && text(reply).contains("Failed to verify"), "refused before any encryption, got id " + id(reply));
        }
      }
    } finally {
      sessionServer.stop(0);
    }
  }

  private static final String PRELOGV = """
      package prelogv;
      import com.velocitypowered.api.event.Subscribe;
      import com.velocitypowered.api.event.connection.DisconnectEvent;
      import com.velocitypowered.api.event.connection.PreLoginEvent;
      import com.velocitypowered.api.event.connection.PreLoginEvent.PreLoginComponentResult;
      import com.velocitypowered.api.event.permission.PermissionsSetupEvent;
      import com.velocitypowered.api.plugin.Plugin;
      import com.velocitypowered.api.proxy.LoginPhaseConnection;
      import com.velocitypowered.api.proxy.Player;
      import net.kyori.adventure.text.Component;
      import net.kyori.adventure.text.format.NamedTextColor;

      @Plugin(id = "prelogv", name = "PreLogV", version = "1")
      public final class PreLogV {
        @SuppressWarnings("unchecked")
        static void signal(String value) { ((java.util.Queue<String>) System.getProperties().get("flow.test.signals")).add(value); }
        @Subscribe public void pre(PreLoginEvent event) {
          var connection = event.getConnection();
          signal("pre:" + event.getUsername() + ":" + event.getUniqueId() + ":" + connection.getProtocolVersion().getProtocol() + ":"
              + connection.getVirtualHost().map(host -> host.getHostString() + "/" + host.getPort()).orElse("-") + ":"
              + connection.getHandshakeIntent() + ":" + (connection instanceof LoginPhaseConnection) + ":"
              + connection.getRemoteAddress().getAddress().isLoopbackAddress() + ":" + event.getResult().isAllowed());
          switch (event.getUsername()) {
            case "VDenied" -> event.setResult(PreLoginComponentResult.denied(Component.text("go away", NamedTextColor.RED)));
            case "VCracked" -> event.setResult(PreLoginComponentResult.forceOfflineMode());
            default -> { }
          }
        }
        @Subscribe public void setup(PermissionsSetupEvent event) {
          if (event.getSubject() instanceof Player player) signal("setup:" + player.getUsername());
        }
        @Subscribe public void gone(DisconnectEvent event) { signal("gone:" + event.getPlayer().getUsername()); }
      }
      """;

  private static void velocityPreLoginResultsMapOntoTheNativeEvent() throws Exception {
    Queue<String> signals = new ConcurrentLinkedQueue<>();
    System.getProperties().put("flow.test.signals", signals);
    Path plugins = compiledPlugin("prelogv.PreLogV", PRELOGV);
    CountingAuthenticator sessions = new CountingAuthenticator();
    try (Backend lobby = new Backend("lobby"); Proxy proxy = new Proxy(lobby, ONLINE, sessions, plugins)) {
      require(waitFor(() -> proxy.runtime.plugins().plugin("prelogv").isPresent(), 10_000), "prelogv enabled");
      try (Client denied = Client.open(proxy.port(), "VDenied")) {
        byte[] reply = denied.readDirect();
        require(id(reply) == 0 && text(reply).contains("go away") && text(reply).contains("red"), "denied at login, got " + text(reply));
      }
      require(signals.contains("pre:VDenied:null:47:localhost/25565:LOGIN:true:true:true"), "the connection as Velocity sees it: " + signals);
      try (Client cracked = Client.join(proxy.port(), "VCracked")) {
        require(proxy.recorder.await(PlayerPostLoginEvent.class, 1), "forced offline, joined without encrypting");
        require(!proxy.runtime.player("VCracked").orElseThrow().authenticated() && sessions.verified.get() == 0, "and unverified");
      }
      require(waitFor(() -> signals.contains("gone:VCracked"), 10_000), "the forced-offline player left");
      require(!signals.contains("setup:VDenied") && !signals.contains("gone:VDenied"), "a denied login is never a Velocity player: " + signals);
    }
  }

  // --- harness -------------------------------------------------------------------------------------

  private static final AuthenticationSettings ONLINE = new AuthenticationSettings(AuthenticationMode.ONLINE, "http://127.0.0.1:1/unused", 1000);

  /** An online-mode check that trusts every client, counts how often it is asked, and gives each a skin. */
  static final class CountingAuthenticator implements PlayerAuthenticator {
    final AtomicInteger verified = new AtomicInteger();
    @Override public AuthenticationMode mode() { return AuthenticationMode.ONLINE; }
    @Override public PlayerProfile verify(gg.tame.conduit.auth.SessionQuery query) {
      verified.incrementAndGet();
      return new PlayerProfile(UUID.nameUUIDFromBytes(query.username().getBytes(StandardCharsets.UTF_8)), query.username(),
          List.of(new ProfileProperty("textures", "c2tpbg==", Optional.of("c2ln"))), true);
    }
  }

  static Path compiledPlugin(String mainClass, String source) throws Exception {
    Path root = TempFiles.dir("login-flow-plugin");
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    VelocityCompatTests.jar(VelocityCompatTests.compile(root, mainClass, source, List.of(), true),
        plugins.resolve(mainClass.substring(0, mainClass.indexOf('.')) + ".jar"), null);
    return plugins;
  }

  /** A proxy in front of the given backends, recording every event. */
  static final class Proxy implements AutoCloseable {
    final MinecraftProxy proxy;
    final ConduitRuntime runtime;
    final Recorder recorder = new Recorder();
    final Plugin owner = new TestPlugin("flow-probe");
    private final Thread serving;
    Proxy(Backend backend, AuthenticationSettings auth, PlayerAuthenticator authenticator, Path plugins) throws Exception {
      this(List.of(backend.server()), auth, authenticator, plugins, ForwardingMode.NONE, Optional.empty());
    }
    Proxy(List<BackendServer> backends, AuthenticationSettings auth, PlayerAuthenticator authenticator, Path plugins,
          ForwardingMode forwarding, Optional<Path> secret) throws Exception {
      OpsSettings ops = new OpsSettings(OpsSettings.CURRENT_SCHEMA, null, new HealthSettings(false, 10_000, 1_500, 3, 2), null, null, null, null, null, null);
      List<String> names = backends.stream().map(BackendServer::name).toList();
      ConduitConfiguration configuration = new ConduitConfiguration(new InetSocketAddress("127.0.0.1", reservePort()), 1 << 20,
          forwarding, secret, backends, List.of(names.getFirst()), names, auth, Optional.empty(), ops);
      proxy = new MinecraftProxy(configuration,
          authenticator != null ? authenticator : gg.tame.conduit.auth.Authenticators.create(auth),
          gg.tame.conduit.crypto.RsaKeys.generate(), plugins != null ? plugins : TempFiles.dir("login-flow").resolve("plugins"));
      runtime = proxy.runtime();
      runtime.events().register(owner, recorder);
      serving = Thread.ofPlatform().daemon().name("login-flow-serve").start(() -> {
        try { proxy.serve(); } catch (Exception ignored) { }
      });
    }
    int port() throws IOException { return proxy.port(); }
    @Override public void close() throws Exception {
      proxy.close();
      serving.join(10_000);
    }
  }

  static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
