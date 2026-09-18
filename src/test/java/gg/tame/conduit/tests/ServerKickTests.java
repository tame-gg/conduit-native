// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.NativeApiTests.reservePort;
import static gg.tame.conduit.tests.NativeApiTests.waitFor;

import gg.tame.conduit.api.event.player.PlayerDisconnectEvent;
import gg.tame.conduit.api.event.player.PlayerKickedFromServerEvent;
import gg.tame.conduit.api.event.player.PlayerKickedFromServerEvent.KickResult;
import gg.tame.conduit.api.event.player.PlayerServerSwitchFailedEvent;
import gg.tame.conduit.api.player.ConnectResult;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.api.server.RegisteredServer;
import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.api.text.TextColor;
import gg.tame.conduit.config.AuthenticationSettings;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.config.ForwardingMode;
import gg.tame.conduit.config.HealthSettings;
import gg.tame.conduit.config.OpsSettings;
import gg.tame.conduit.network.MinecraftProxy;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.text.ComponentCodec;
import gg.tame.conduit.runtime.ConduitRuntime;
import gg.tame.conduit.tests.NativeApiTests.Recorder;
import gg.tame.conduit.tests.NativeApiTests.TestPlugin;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PlayerKickedFromServerEvent, and Velocity's KickedFromServerEvent on it, however a backend turns a
 * player away: refusing their first server, a switch or a fallback, or kicking them while they play,
 * and now also in the Configuration phase a 1.20.2+ server has after Login. Scripted 1.20.4 clients
 * and backends on loopback.
 */
public final class ServerKickTests {
  public static void main(String[] a) throws Exception { run(); }

  public static void run() throws Exception {
    aFirstServerThatRefusesInConfigurationIsAKickDuringConnect();
    listenersDecideAConfigurationRefusalButCannotRedirectIt();
    aLegacyClientRefusedInItsServersConfigurationIsNotOfferedAnother();
    aSwitchRefusedAtLoginKeepsThePlayerAndInConfigurationEndsTheSession();
    aFallbackRefusedInConfigurationEndsTheSessionOnce();
    aRefusedRedirectAfterAKickIsDecidedByItsOwnEvent();
    velocityPluginsSeeAConfigurationRefusalAsAKickDuringConnect();
    System.out.println("ServerKickTests OK");
  }

  static final ProtocolDefinition P765 = ProtocolDefinition.forVersion(765);
  /** Translatable, coloured: what a vanilla or NeoForge server writes, and what Text alone would lose. */
  private static final String INCOMPATIBLE = "{\"translate\":\"multiplayer.disconnect.incompatible\",\"with\":[\"NeoForge 20.4\"],\"color\":\"red\"}";

  // --- the first server ---------------------------------------------------------------------------

  /**
   * A Configuration Disconnect from the first server was relayed as it was: no plugin heard of it,
   * and the proxy took the closed backend for a lost one and ran a fallback for a player who was
   * already looking at the disconnect screen.
   */
  private static void aFirstServerThatRefusesInConfigurationIsAKickDuringConnect() throws Exception {
    try (Backend765 lobby = new Backend765("lobby"); Backend765 survival = new Backend765("survival");
         Proxy proxy = new Proxy(List.of(lobby, survival), List.of("lobby", "survival"), List.of("survival"), null)) {
      lobby.refuseInConfiguration(INCOMPATIBLE);
      try (Client765 client = Client765.open(proxy.port(), "Modless")) {
        byte[] refused = client.await(ConnectionState.CONFIGURATION, PacketKind.CONFIGURATION_DISCONNECT, "the refusal");
        require(nbtJson(refused).equals(roundTrip(INCOMPATIBLE)), "shown exactly as the backend wrote it, got " + nbtJson(refused));
        require(client.ends(), "and the connection ends");
      }
      require(proxy.recorder.await(PlayerDisconnectEvent.class, 1), "the player is gone");
      PlayerKickedFromServerEvent kicked = only(proxy.recorder.of(PlayerKickedFromServerEvent.class));
      require(kicked.server().getName().equals("lobby") && kicked.duringConnect() && kicked.result() instanceof KickResult.Disconnect
          && kicked.reason().isPresent(), "a kick during connect, Disconnect by default, got " + kicked.result());
      require(survival.logins.get() == 0, "the client is past Login, so no other server is tried: no fallback, no next candidate");
      require(proxy.recorder.of(PlayerDisconnectEvent.class).size() == 1, "one disconnect event");
    }
  }

  private static void listenersDecideAConfigurationRefusalButCannotRedirectIt() throws Exception {
    try (Backend765 lobby = new Backend765("lobby"); Backend765 survival = new Backend765("survival");
         Proxy proxy = new Proxy(List.of(lobby, survival), List.of("lobby"), List.of("survival"), null)) {
      lobby.refuseInConfiguration(INCOMPATIBLE);
      RegisteredServer survivalView = proxy.runtime.servers().getServer("survival").orElseThrow();
      proxy.recorder.hook = event -> {
        if (!(event instanceof PlayerKickedFromServerEvent kicked)) return;
        switch (kicked.player().username()) {
          case "Custom" -> kicked.setResult(new KickResult.Disconnect(Text.of("Custom reason").color(TextColor.GOLD)));
          case "Notified" -> kicked.setResult(new KickResult.Notify(Text.of("Told in the end")));
          case "Redirected" -> kicked.setResult(new KickResult.Redirect(survivalView, Optional.of(Text.of("moved"))));
          default -> { }
        }
      };
      String custom = refusalShown(proxy, "Custom");
      require(custom.contains("Custom reason") && custom.contains("gold"), "a listener's Disconnect is the reason shown, got " + custom);
      require(refusalShown(proxy, "Notified").contains("Told in the end"), "Notify disconnects with its message: there is nowhere to stay");
      require(refusalShown(proxy, "Redirected").equals(roundTrip(INCOMPATIBLE)),
          "a Redirect cannot be honoured from a configuration phase: the backend's reason is shown");
      require(survival.logins.get() == 0, "and no other server was dialled");
      require(proxy.recorder.of(PlayerKickedFromServerEvent.class).size() == 3, "one kick each");
    }
  }

  /** The configuration disconnect a new client named {@code name} is shown, as JSON-ish text for matching. */
  private static String refusalShown(Proxy proxy, String name) throws Exception {
    try (Client765 client = Client765.open(proxy.port(), name)) {
      return nbtJson(client.await(ConnectionState.CONFIGURATION, PacketKind.CONFIGURATION_DISCONNECT, name + "'s refusal"));
    }
  }

  /**
   * A client older than 1.20.2 is sent its first server's Login Success before Conduit runs that
   * server's configuration for it. A refusal there was taken for an unreachable server: its reason
   * was lost, and the next candidate's Login Success went to a client already in Play.
   */
  private static void aLegacyClientRefusedInItsServersConfigurationIsNotOfferedAnother() throws Exception {
    ProtocolDefinition p393 = ProtocolDefinition.forVersion(393);
    try (Backend765 lobby = new Backend765("lobby"); Backend765 survival = new Backend765("survival");
         Proxy proxy = new Proxy(List.of(lobby, survival), List.of("lobby", "survival"), List.of("survival"), null)) {
      lobby.refuseInConfiguration("{\"text\":\"Modern clients only\"}");
      proxy.proxy.probeBackends();
      require(proxy.runtime.selector().resolveProtocol("lobby").orElse(-1) == 765, "Conduit knows lobby speaks 1.20.4");
      try (Socket socket = new Socket("127.0.0.1", proxy.port())) {
        socket.setSoTimeout(15_000);
        MinecraftFrames.write(socket.getOutputStream(), new Handshake(393, "localhost", 25565, 2).encode());
        MinecraftFrames.write(socket.getOutputStream(), packet(0, output -> MinecraftOutput.string(output, "Oldtimer")));
        byte[] success = MinecraftFrames.read(socket.getInputStream(), 1 << 20);
        require(NativeApiTests.id(success) == p393.id(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_SUCCESS),
            "the 1.13 client gets lobby's Login Success, got id " + NativeApiTests.id(success));
        byte[] refused = MinecraftFrames.read(socket.getInputStream(), 1 << 20);
        require(NativeApiTests.id(refused) == p393.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DISCONNECT)
            && NativeApiTests.text(refused).contains("Modern clients only"), "then lobby's reason, got " + NativeApiTests.text(refused));
        try { MinecraftFrames.read(socket.getInputStream(), 1 << 20); throw new AssertionError("nothing follows the refusal"); }
        catch (IOException closed) { }
      }
      require(proxy.recorder.await(PlayerDisconnectEvent.class, 1), "gone");
      PlayerKickedFromServerEvent kicked = only(proxy.recorder.of(PlayerKickedFromServerEvent.class));
      require(kicked.server().getName().equals("lobby") && kicked.duringConnect() && kicked.result() instanceof KickResult.Disconnect,
          "a kick during connect, Disconnect by default: " + kicked.result());
      require(survival.logins.get() == 0, "and the next candidate was never offered a client already past Login");
    }
  }

  // --- switches and fallbacks ----------------------------------------------------------------------

  private static void aSwitchRefusedAtLoginKeepsThePlayerAndInConfigurationEndsTheSession() throws Exception {
    try (Backend765 lobby = new Backend765("lobby"); Backend765 survival = new Backend765("survival");
         Proxy proxy = new Proxy(List.of(lobby, survival), List.of("lobby"), List.of("lobby"), null)) {
      RegisteredServer survivalView = proxy.runtime.servers().getServer("survival").orElseThrow();
      try (Client765 client = Client765.open(proxy.port(), "Switcher")) {
        client.await(ConnectionState.PLAY, PacketKind.PLAY_LOGIN, "Join Game from lobby");
        Player player = proxy.runtime.player("Switcher").orElseThrow();

        // At Login: the client never left Play, so it stays, and is told.
        survival.refuseAtLogin("{\"text\":\"You are not whitelisted on survival\"}");
        ConnectResult atLogin = player.connectWithResult(survivalView).get(15, TimeUnit.SECONDS);
        require(atLogin.status() == ConnectResult.Status.FAILED && player.currentServer().name().equals("lobby"), "stays on lobby, got " + atLogin);
        require(waitFor(() -> client.saw(ConnectionState.PLAY, PacketKind.PLAY_SYSTEM_CHAT, "not whitelisted on survival"), 10_000), "told in chat");
        PlayerKickedFromServerEvent first = only(proxy.recorder.of(PlayerKickedFromServerEvent.class));
        require(first.duringConnect() && first.result() instanceof KickResult.Notify, "a refused switch: Notify by default");

        // In configuration: the client was moved into survival's, so there is nothing to stay on.
        survival.refuseInConfiguration(INCOMPATIBLE);
        ConnectResult inConfiguration = player.connectWithResult(survivalView).get(15, TimeUnit.SECONDS);
        require(inConfiguration.status() == ConnectResult.Status.FAILED, "the switch fails, got " + inConfiguration);
        byte[] refused = client.await(ConnectionState.CONFIGURATION, PacketKind.CONFIGURATION_DISCONNECT, "the refusal");
        require(nbtJson(refused).equals(roundTrip(INCOMPATIBLE)), "shown exactly as the backend wrote it, got " + nbtJson(refused));
        require(client.ends(), "and the session ends");
        require(!client.saw(ConnectionState.CONFIGURATION, PacketKind.CONFIGURATION_DISCONNECT, "Could not connect"),
            "with no second, generic reason behind it");
      }
      require(proxy.recorder.await(PlayerDisconnectEvent.class, 1), "gone");
      List<PlayerKickedFromServerEvent> kicks = proxy.recorder.of(PlayerKickedFromServerEvent.class);
      require(kicks.size() == 2 && kicks.getLast().duringConnect() && kicks.getLast().result() instanceof KickResult.Disconnect
          && kicks.getLast().server().getName().equals("survival"), "a kick during connect, Disconnect by default: " + kicks);
      require(proxy.recorder.names(PlayerServerSwitchFailedEvent.class, PlayerKickedFromServerEvent.class).equals(List.of(
              "PlayerServerSwitchFailedEvent", "PlayerKickedFromServerEvent", "PlayerServerSwitchFailedEvent", "PlayerKickedFromServerEvent")),
          "reported, then decided, in both phases, got " + proxy.recorder.names());
      require(proxy.recorder.of(PlayerDisconnectEvent.class).size() == 1, "one disconnect event");
    }
  }

  private static void aFallbackRefusedInConfigurationEndsTheSessionOnce() throws Exception {
    try (Backend765 lobby = new Backend765("lobby"); Backend765 survival = new Backend765("survival");
         Backend765 spare = new Backend765("spare");
         Proxy proxy = new Proxy(List.of(lobby, survival, spare), List.of("lobby"), List.of("lobby", "survival", "spare"), null)) {
      survival.refuseInConfiguration(INCOMPATIBLE);
      try (Client765 client = Client765.open(proxy.port(), "Faller")) {
        client.await(ConnectionState.PLAY, PacketKind.PLAY_LOGIN, "Join Game from lobby");
        lobby.drop();
        byte[] refused = client.await(ConnectionState.CONFIGURATION, PacketKind.CONFIGURATION_DISCONNECT, "the fallback's refusal");
        require(nbtJson(refused).equals(roundTrip(INCOMPATIBLE)), "the fallback's reason, as it wrote it, got " + nbtJson(refused));
        require(client.ends(), "and the session ends");
      }
      require(proxy.recorder.await(PlayerDisconnectEvent.class, 1), "gone");
      PlayerKickedFromServerEvent kicked = only(proxy.recorder.of(PlayerKickedFromServerEvent.class));
      require(kicked.server().getName().equals("survival") && kicked.duringConnect() && kicked.result() instanceof KickResult.Disconnect,
          "the refused fallback is a kick during connect: " + kicked.result());
      Thread.sleep(300);
      require(spare.logins.get() == 0, "a session that ended is not taken on to the next fallback");
      require(proxy.recorder.of(PlayerDisconnectEvent.class).size() == 1, "one disconnect event");
    }
  }

  /**
   * Kicked while playing and redirected by a listener to a server that refuses too: that refusal's own
   * kicked event was fired and then ignored, whatever it decided.
   */
  private static void aRefusedRedirectAfterAKickIsDecidedByItsOwnEvent() throws Exception {
    try (Backend765 lobby = new Backend765("lobby"); Backend765 strict = new Backend765("strict"); Backend765 survival = new Backend765("survival");
         Proxy proxy = new Proxy(List.of(lobby, strict, survival), List.of("lobby"), List.of("lobby"), null)) {
      strict.refuseAtLogin("{\"text\":\"Strict says no\"}");
      RegisteredServer strictView = proxy.runtime.servers().getServer("strict").orElseThrow();
      RegisteredServer survivalView = proxy.runtime.servers().getServer("survival").orElseThrow();
      proxy.recorder.hook = event -> {
        if (!(event instanceof PlayerKickedFromServerEvent kicked)) return;
        String name = kicked.player().username();
        if (!kicked.duringConnect()) kicked.setResult(new KickResult.Redirect(strictView, Optional.empty()));
        else if (name.equals("GoHome")) kicked.setResult(new KickResult.Disconnect(Text.of("Go home")));
        else if (name.equals("Onward")) kicked.setResult(new KickResult.Redirect(survivalView, Optional.empty()));
      };
      // The refusal's own Disconnect is the reason shown.
      try (Client765 client = Client765.open(proxy.port(), "GoHome")) {
        client.await(ConnectionState.PLAY, PacketKind.PLAY_LOGIN, "Join Game");
        lobby.kick("{\"text\":\"Lobby restarting\"}");
        byte[] shown = client.await(ConnectionState.PLAY, PacketKind.PLAY_DISCONNECT, "the disconnect");
        require(nbtJson(shown).contains("Go home"), "the refused target's decision, got " + nbtJson(shown));
      }
      // Left as Notify, the first server's own reason, as before.
      try (Client765 client = Client765.open(proxy.port(), "Plain")) {
        client.await(ConnectionState.PLAY, PacketKind.PLAY_LOGIN, "Join Game");
        lobby.kick("{\"text\":\"Lobby restarting\"}");
        byte[] shown = client.await(ConnectionState.PLAY, PacketKind.PLAY_DISCONNECT, "the disconnect");
        require(nbtJson(shown).contains("Lobby restarting"), "the first server's reason, got " + nbtJson(shown));
      }
      // Its Redirect names the next server, where the player then plays.
      try (Client765 client = Client765.open(proxy.port(), "Onward")) {
        client.await(ConnectionState.PLAY, PacketKind.PLAY_LOGIN, "Join Game");
        lobby.kick("{\"text\":\"Lobby restarting\"}");
        require(waitFor(() -> proxy.runtime.player("Onward").map(player -> player.currentServer().name().equals("survival")).orElse(false), 15_000),
            "redirected on to survival");
        require(!client.saw(ConnectionState.PLAY, PacketKind.PLAY_DISCONNECT, ""), "and never disconnected");
      }
      require(strict.logins.get() == 3 && survival.logins.get() == 1, "each redirect was tried once");
    }
  }

  // --- Velocity ------------------------------------------------------------------------------------

  private static final String KICKV = """
      package kickv;
      import com.velocitypowered.api.event.Subscribe;
      import com.velocitypowered.api.event.player.KickedFromServerEvent;
      import com.velocitypowered.api.plugin.Plugin;
      import net.kyori.adventure.text.Component;
      import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

      @Plugin(id = "kickv", name = "KickV", version = "1")
      public final class KickV {
        @SuppressWarnings("unchecked")
        static void signal(String value) { ((java.util.Queue<String>) System.getProperties().get("kick.test.signals")).add(value); }
        @Subscribe public void kicked(KickedFromServerEvent event) {
          signal("kicked:" + event.getPlayer().getUsername() + ":" + event.getServer().getServerInfo().getName() + ":"
              + event.kickedDuringServerConnect() + ":" + event.getResult().getClass().getSimpleName() + ":"
              + event.getServerKickReason().map(reason -> PlainTextComponentSerializer.plainText().serialize(reason)).orElse("none"));
          if (event.getPlayer().getUsername().equals("VNotified")) {
            event.setResult(KickedFromServerEvent.Notify.create(Component.text("velocity says hi")));
          }
        }
      }
      """;

  private static void velocityPluginsSeeAConfigurationRefusalAsAKickDuringConnect() throws Exception {
    Queue<String> signals = new ConcurrentLinkedQueue<>();
    System.getProperties().put("kick.test.signals", signals);
    Path root = TempFiles.dir("kick-velocity");
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    VelocityCompatTests.jar(VelocityCompatTests.compile(root, "kickv.KickV", KICKV, List.of(), true), plugins.resolve("KickV.jar"), null);
    try (Backend765 lobby = new Backend765("lobby"); Proxy proxy = new Proxy(List.of(lobby), List.of("lobby"), List.of("lobby"), plugins)) {
      require(waitFor(() -> proxy.runtime.plugins().plugin("kickv").isPresent(), 10_000), "kickv enabled");
      lobby.refuseInConfiguration("{\"text\":\"No mods, no entry\"}");
      require(refusalShown(proxy, "VPlain").contains("No mods, no entry"), "left alone, the backend's reason");
      require(waitFor(() -> signals.contains("kicked:VPlain:lobby:true:DisconnectPlayer:No mods, no entry"), 10_000),
          "a KickedFromServerEvent during connect, DisconnectPlayer by default: " + signals);
      require(refusalShown(proxy, "VNotified").contains("velocity says hi"), "a Velocity Notify's message ends the session");
    }
  }

  // --- harness -------------------------------------------------------------------------------------

  private static <T> T only(List<T> items) {
    require(items.size() == 1, "exactly one, got " + items);
    return items.getFirst();
  }
  /** A 1.20.3+ text component read back from a packet's body, as JSON. */
  private static String nbtJson(byte[] packet) throws IOException {
    DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet));
    MinecraftInput.varInt(input);
    return ComponentCodec.nbtToJson(input);
  }
  /** {@code json} as it reads back after the NBT a backend would send it as. */
  private static String roundTrip(String json) throws IOException { return ComponentCodec.nbtBytesToJson(ComponentCodec.jsonToNbtBytes(json)); }

  private static int id(ConnectionState state, PacketDirection direction, PacketKind kind) { return P765.id(state, direction, kind); }
  private static byte[] packet(int id, NativeApiTests.Body body) throws IOException { return NativeApiTests.packet(id, body); }

  /** A proxy over the given 1.20.4 backends, recording every event. */
  private static final class Proxy implements AutoCloseable {
    final MinecraftProxy proxy;
    final ConduitRuntime runtime;
    final Recorder recorder = new Recorder();
    private final Thread serving;
    Proxy(List<Backend765> backends, List<String> initial, List<String> fallback, Path plugins) throws Exception {
      OpsSettings ops = new OpsSettings(OpsSettings.CURRENT_SCHEMA, null, new HealthSettings(false, 10_000, 1_500, 3, 2), null, null, null, null, null, null);
      ConduitConfiguration configuration = new ConduitConfiguration(new InetSocketAddress("127.0.0.1", reservePort()), 1 << 20,
          ForwardingMode.NONE, Optional.empty(), backends.stream().map(Backend765::server).toList(), initial, fallback,
          AuthenticationSettings.offline(), Optional.empty(), ops);
      proxy = new MinecraftProxy(configuration, gg.tame.conduit.auth.Authenticators.create(configuration.authentication()),
          gg.tame.conduit.crypto.RsaKeys.generate(), plugins != null ? plugins : TempFiles.dir("server-kick").resolve("plugins"));
      runtime = proxy.runtime();
      runtime.events().register(new TestPlugin("kick-probe"), recorder);
      serving = Thread.ofPlatform().daemon().name("kick-test-serve").start(() -> {
        try { proxy.serve(); } catch (Exception ignored) { }
      });
    }
    int port() throws IOException { return proxy.port(); }
    @Override public void close() throws Exception {
      proxy.close();
      serving.join(10_000);
    }
  }

  /**
   * A 1.20.4 server. By default it logs the player in and configures them at once (Finish
   * Configuration, then Join Game); it can instead refuse them at Login, or in Configuration.
   */
  static final class Backend765 implements AutoCloseable {
    private final String name;
    private final ServerSocket listener = new ServerSocket(0);
    final AtomicInteger logins = new AtomicInteger();
    private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<>());
    private volatile Socket current;
    private volatile String refuseAtLogin;
    private volatile String refuseInConfiguration;
    Backend765(String name) throws IOException {
      this.name = name;
      Thread.ofPlatform().daemon().name("kick-backend-" + name).start(() -> {
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
    void refuseAtLogin(String json) { refuseAtLogin = json; refuseInConfiguration = null; }
    void refuseInConfiguration(String json) { refuseInConfiguration = json; refuseAtLogin = null; }
    private void serve(Socket socket) {
      try (socket) {
        socket.setSoTimeout(15_000);
        InputStream in = socket.getInputStream();
        if (Handshake.decode(MinecraftFrames.read(in, 4096)).nextState() != 2) {
          MinecraftFrames.read(in, 4096);
          write(socket, packet(0, output -> MinecraftOutput.string(output, "{\"version\":{\"name\":\"1.20.4\",\"protocol\":765}}")));
          return;
        }
        MinecraftFrames.read(in, 4096);
        logins.incrementAndGet();
        String atLogin = refuseAtLogin;
        if (atLogin != null) {
          write(socket, packet(id(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_DISCONNECT),
              output -> MinecraftOutput.string(output, atLogin)));
          finish(socket);
          return;
        }
        write(socket, packet(id(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_SUCCESS), output -> {
          output.writeLong(0); output.writeLong(7); MinecraftOutput.string(output, "player"); MinecraftOutput.varInt(output, 0);
        }));
        readUntil(in, id(ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, PacketKind.LOGIN_ACKNOWLEDGED));
        String inConfiguration = refuseInConfiguration;
        if (inConfiguration != null) {
          write(socket, packet(id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_DISCONNECT),
              output -> ComponentCodec.jsonToNbt(output, inConfiguration)));
          finish(socket);
          return;
        }
        write(socket, new byte[] {(byte) id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_FINISH)});
        readUntil(in, id(ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_FINISH));
        socket.setSoTimeout(0);
        current = socket;
        write(socket, DirectLoginCompressionTests.joinGame765());
        while (true) MinecraftFrames.read(in, 1 << 20);
      } catch (Exception ended) { }
    }
    /**
     * Stops writing and waits for the proxy to hang up. Closed at once with the proxy's bytes still
     * unread, the socket is reset, and a reset can take the disconnect it follows with it.
     */
    private static void finish(Socket socket) throws IOException {
      socket.shutdownOutput();
      socket.setSoTimeout(5_000);
      try { while (socket.getInputStream().read() >= 0) { } } catch (IOException gone) { }
    }
    private static void readUntil(InputStream in, int wanted) throws IOException {
      while (true) {
        byte[] frame = MinecraftFrames.read(in, 1 << 20);
        if (frame.length > 0 && frame[0] == wanted) return;
      }
    }
    /** A Play Disconnect, as a 1.20.4 server kicks a player. */
    void kick(String json) throws IOException {
      write(current, packet(id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DISCONNECT),
          output -> ComponentCodec.jsonToNbt(output, json)));
    }
    /** The server going away under a player, as a crash would. */
    void drop() throws IOException { current.close(); }
    @Override public void close() throws IOException {
      listener.close();
      synchronized (sockets) { for (Socket socket : sockets) socket.close(); }
    }
    private static void write(Socket socket, byte[] packet) throws IOException {
      synchronized (socket) { MinecraftFrames.write(socket.getOutputStream(), packet); }
    }
  }

  /**
   * A 1.20.4 client that keeps up with the proxy the way the real one does: it acknowledges Login
   * Success, finishes configuration when told to, and re-enters it on Start Configuration. Every packet
   * is kept with the state it arrived in.
   */
  static final class Client765 implements AutoCloseable {
    private record Frame(ConnectionState state, byte[] data) {}
    private final Socket socket;
    private final List<Frame> received = Collections.synchronizedList(new ArrayList<>());
    private volatile ConnectionState state = ConnectionState.LOGIN;
    private volatile boolean ended;
    private Client765(Socket socket) { this.socket = socket; }
    static Client765 open(int port, String name) throws IOException {
      Socket socket = new Socket("127.0.0.1", port);
      socket.setSoTimeout(20_000);
      Client765 client = new Client765(socket);
      MinecraftFrames.write(socket.getOutputStream(), new Handshake(765, "localhost", 25565, 2).encode());
      MinecraftFrames.write(socket.getOutputStream(), packet(id(ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, PacketKind.LOGIN_START), output -> {
        MinecraftOutput.string(output, name);
        UUID offline = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        output.writeLong(offline.getMostSignificantBits()); output.writeLong(offline.getLeastSignificantBits());
      }));
      Thread.ofPlatform().daemon().name("kick-client-" + name).start(client::read);
      return client;
    }
    private void read() {
      try {
        while (true) {
          byte[] frame = MinecraftFrames.read(socket.getInputStream(), 1 << 21);
          ConnectionState at = state;
          received.add(new Frame(at, frame));
          int packetId = NativeApiTests.id(frame);
          if (at == ConnectionState.LOGIN && packetId == id(at, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_SUCCESS)) {
            state = ConnectionState.CONFIGURATION;
            send(new byte[] {(byte) id(ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, PacketKind.LOGIN_ACKNOWLEDGED)});
          } else if (at == ConnectionState.CONFIGURATION && packetId == id(at, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_FINISH)) {
            state = ConnectionState.PLAY;
            send(new byte[] {(byte) id(ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_FINISH)});
          } else if (at == ConnectionState.PLAY && packetId == id(at, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_START_CONFIGURATION)) {
            state = ConnectionState.CONFIGURATION;
            send(new byte[] {(byte) id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CONFIGURATION_ACKNOWLEDGED)});
          }
        }
      } catch (IOException closed) {
        ended = true;
      }
    }
    private synchronized void send(byte[] packet) throws IOException { MinecraftFrames.write(socket.getOutputStream(), packet); }
    /** The first packet of {@code kind} received in {@code state}, waited for. */
    byte[] await(ConnectionState in, PacketKind kind, String what) throws InterruptedException {
      int wanted = id(in, PacketDirection.SERVER_TO_CLIENT, kind);
      byte[][] found = new byte[1][];
      require(waitFor(() -> {
        synchronized (received) {
          for (Frame frame : received) {
            if (frame.state == in && NativeApiTests.id(frame.data) == wanted) { found[0] = frame.data; return true; }
          }
        }
        return false;
      }, 15_000), "the client never got " + what + "; it got " + describe());
      return found[0];
    }
    /** Whether a packet of {@code kind} in {@code state} arrived whose bytes contain {@code text}. */
    boolean saw(ConnectionState in, PacketKind kind, String text) {
      int wanted = id(in, PacketDirection.SERVER_TO_CLIENT, kind);
      synchronized (received) {
        return received.stream().anyMatch(frame -> frame.state == in && NativeApiTests.id(frame.data) == wanted
            && new String(frame.data, StandardCharsets.UTF_8).contains(text));
      }
    }
    boolean ends() throws InterruptedException { return waitFor(() -> ended, 10_000); }
    private String describe() {
      synchronized (received) {
        return received.stream().map(frame -> frame.state + ":0x" + Integer.toHexString(NativeApiTests.id(frame.data))).toList().toString();
      }
    }
    @Override public void close() throws IOException { socket.close(); }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
