package gg.tame.conduit.tests;

import gg.tame.conduit.brand.ServerBrand;
import gg.tame.conduit.command.CommandManager;
import gg.tame.conduit.command.CommandSource;
import gg.tame.conduit.command.CoreCommands;
import gg.tame.conduit.command.RegisteredCommand;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.config.ForwardingMode;
import gg.tame.conduit.network.MinecraftProxy;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.PluginMessage;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.ProtocolSession;
import gg.tame.conduit.routing.ServerMatch;
import gg.tame.conduit.routing.ServerRegistry;
import gg.tame.conduit.session.SessionLifecycle;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class Phase6Tests {
  public static void run() throws Exception {
    serverBrandTransformations();
    commandFramework();
    serverNameMatching();
    sessionLifecycleTransitions();
    switchBetweenMockBackends();
    failedSwitchKeepsCurrentBackend();
    commandGraphMergeKeepsIndexesValid();
    Phase7Tests.run();
  }
  private static void serverBrandTransformations() {
    require(ServerBrand.display("Paper").equals("Paper (Conduit)"), "Paper brand");
    require(ServerBrand.display("Purpur").equals("Purpur (Conduit)"), "Purpur brand");
    require(ServerBrand.display("Fabric").equals("Fabric (Conduit)"), "Fabric brand");
    require(ServerBrand.display("Paper (Conduit)").equals("Paper (Conduit)"), "duplicate suffix");
    require(ServerBrand.display("").equals("Conduit"), "empty brand");
    require(ServerBrand.display(null).equals("Conduit"), "missing brand");
  }
  private static void commandFramework() throws Exception {
    CommandManager manager = new CommandManager();
    List<String> ran = new ArrayList<>();
    RecordingSource source = new RecordingSource("lobby", true);
    manager.register(new RegisteredCommand("alpha", List.of("a"), "conduit.test", (s, args) -> ran.add("alpha:" + String.join(",", args)), (s, args) -> List.of("one", "two")));
    require(manager.dispatch(source, "/a foo"), "alias dispatch");
    require(ran.equals(List.of("alpha:foo")), "alias payload");
    RecordingSource denied = new RecordingSource("lobby", false);
    require(manager.dispatch(denied, "alpha"), "permission still handled");
    require(denied.messages.getFirst().contains("permission"), "permission message");
    require(manager.tabComplete(source, "/a ").equals(List.of("one", "two")), "tab complete args");
    require(manager.tabComplete(source, "/al").equals(List.of("alpha")), "tab complete name");
    manager.unregister("alpha");
    require(!manager.dispatch(source, "alpha"), "unregistered");
    Path config = Files.createTempFile("conduit", ".toml");
    Files.writeString(config, "[listener]\nhost=\"127.0.0.1\"\nport=25565\nmax-frame-bytes=64\n[forwarding]\nmode=\"none\"\n[servers.lobby]\nhost=\"127.0.0.1\"\nport=1\n[servers.survival]\nhost=\"127.0.0.1\"\nport=2\n[servers.minigames]\nhost=\"127.0.0.1\"\nport=3\n[servers.minigames-2]\nhost=\"127.0.0.1\"\nport=4\n[routing]\ninitial=[\"lobby\"]\nfallback=[\"lobby\"]\n");
    ServerRegistry registry = new ServerRegistry(gg.tame.conduit.config.ConfigurationLoader.load(config));
    CommandManager core = new CommandManager();
    CoreCommands.register(core, registry, new gg.tame.conduit.session.PlayerManager());
    RecordingSource player = new RecordingSource("lobby", true);
    core.dispatch(player, "/server");
    require(player.messages.stream().anyMatch(line -> line.equals("You are currently connected to: lobby")), "current server");
    require(player.messages.stream().anyMatch(line -> line.equals("  ● lobby")), "current marker");
    require(player.messages.stream().anyMatch(line -> line.equals("  ○ survival")), "other marker");
    require(player.messages.stream().noneMatch(line -> line.contains("127.0.0.1")), "server list leaked address");
    require(player.messages.stream().noneMatch(line -> line.contains("click") || line.contains("╭")), "no fancy ui");
    core.dispatch(player, "/server lobby");
    require(player.messages.stream().anyMatch(line -> line.contains("already connected to lobby")), "already connected");
    core.dispatch(player, "/server mini");
    require(player.messages.stream().anyMatch(line -> line.equals("Multiple servers match:")), "ambiguous");
    core.dispatch(player, "/server missing");
    require(player.messages.stream().anyMatch(line -> line.equals("Unknown server: missing")), "unknown");
    core.dispatch(player, "/conduit");
    require(player.messages.stream().anyMatch(line -> line.startsWith("Conduit ")), "conduit version");
    require(player.messages.stream().anyMatch(line -> line.equals("Current server: lobby")), "conduit current server");
    require(core.tabComplete(player, "/server s").equals(List.of("survival")), "server tab filter");
    require(core.dispatch(player, "/lobby"), "slash-server alias");
    core.dispatch(player, "/conduit servers");
    require(player.messages.stream().anyMatch(line -> line.startsWith("Servers:")), "conduit servers");
    core.dispatch(player, "/conduit help");
    require(player.messages.stream().anyMatch(line -> line.contains("/server")), "conduit help");
  }
  private static void serverNameMatching() throws Exception {
    Path config = Files.createTempFile("conduit", ".toml");
    Files.writeString(config, "[listener]\nhost=\"127.0.0.1\"\nport=25565\nmax-frame-bytes=64\n[forwarding]\nmode=\"none\"\n[servers.lobby]\nhost=\"127.0.0.1\"\nport=1\n[servers.survival]\nhost=\"127.0.0.1\"\nport=2\n[routing]\ninitial=[\"lobby\"]\nfallback=[\"survival\"]\n");
    ServerRegistry registry = new ServerRegistry(gg.tame.conduit.config.ConfigurationLoader.load(config));
    require(registry.resolve("surv").kind() == ServerMatch.Kind.UNIQUE, "partial");
    require(registry.resolve("survival").server().orElseThrow().name().equals("survival"), "exact");
    require(registry.contains("LOBBY"), "normalized contains");
  }
  private static void sessionLifecycleTransitions() {
    require(SessionLifecycle.CONNECTING != SessionLifecycle.CLOSED, "states exist");
    ProtocolSession session = new ProtocolSession();
    session.acceptHandshake(2);
    session.beginConfiguration();
    session.beginPlay();
    session.beginReconfiguration();
    require(session.state() == ConnectionState.CONFIGURATION, "play to configuration");
    session.beginPlay();
    require(session.state() == ConnectionState.PLAY, "reconfigured play");
  }
  private static void switchBetweenMockBackends() throws Exception {
    Path secret = Files.createTempFile("conduit-forwarding", ".secret"); Files.writeString(secret, "wire-secret");
    try (ServerSocket lobby = new ServerSocket(0); ServerSocket survival = new ServerSocket(0)) {
      AtomicReference<String> lobbyUuid = new AtomicReference<>();
      AtomicReference<String> survivalUuid = new AtomicReference<>();
      Thread lobbyThread = Thread.startVirtualThread(() -> serveBackend(lobby, "Paper", (byte) 1, lobbyUuid));
      Thread survivalThread = Thread.startVirtualThread(() -> serveBackend(survival, "Paper", (byte) 2, survivalUuid));
      ConduitConfiguration configuration = new ConduitConfiguration(new InetSocketAddress("127.0.0.1", reservePort()), 4096,
          ForwardingMode.MODERN, Optional.of(secret),
          List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", lobby.getLocalPort())),
              new BackendServer("survival", new InetSocketAddress("127.0.0.1", survival.getLocalPort()))),
          List.of("lobby"), List.of("lobby"));
      try (MinecraftProxy proxy = new MinecraftProxy(configuration)) {
        int proxyPort = proxy.port();
        Thread serving = Thread.startVirtualThread(() -> { try { proxy.serve(); } catch (Exception ignored) { } });
        try (Socket client = new Socket("127.0.0.1", proxyPort)) {
          MinecraftFrames.write(client.getOutputStream(), new byte[] {0, (byte) 0xFD, 5, 5, 'l', 'o', 'c', 'a', 'l', 0x63, (byte) 0xDD, 2});
          MinecraftFrames.write(client.getOutputStream(), loginStart());
          require(java.util.Arrays.equals(MinecraftFrames.read(client.getInputStream(), 4096), new byte[] {2}), "lobby Login Success");
          byte[] brand = MinecraftFrames.read(client.getInputStream(), 4096);
          require(new String(brand).contains("Paper (Conduit)") || PlayPackets.packetId(brand) == 0, "brand or finish");
          if (PlayPackets.packetId(brand) == 0) {
            PluginMessage message = PluginMessage.decodeBody(java.util.Arrays.copyOfRange(brand, 1, brand.length), 4096);
            require(message.brandText().equals("Paper (Conduit)"), "rewritten brand");
            require(java.util.Arrays.equals(MinecraftFrames.read(client.getInputStream(), 4096), new byte[] {2}), "lobby finish");
          }
          MinecraftFrames.write(client.getOutputStream(), new byte[] {2});
          MinecraftFrames.write(client.getOutputStream(), chatCommand("server survival"));
          byte[] start = readUntilPacket(client, 0x67);
          require(PlayPackets.packetId(start) == 0x67, "start configuration");
          MinecraftFrames.write(client.getOutputStream(), new byte[] {0x0B});
          byte[] maybeBrand = MinecraftFrames.read(client.getInputStream(), 4096);
          if (PlayPackets.packetId(maybeBrand) == 0) {
            require(PluginMessage.decodeBody(java.util.Arrays.copyOfRange(maybeBrand, 1, maybeBrand.length), 4096).brandText().equals("Paper (Conduit)"), "survival brand");
            require(java.util.Arrays.equals(MinecraftFrames.read(client.getInputStream(), 4096), new byte[] {2}), "survival finish");
          } else require(java.util.Arrays.equals(maybeBrand, new byte[] {2}), "survival finish");
          MinecraftFrames.write(client.getOutputStream(), new byte[] {2});
          MinecraftFrames.write(client.getOutputStream(), chatCommand("server lobby"));
          byte[] startBack = readUntilPacket(client, 0x67);
          require(PlayPackets.packetId(startBack) == 0x67, "switch back start configuration");
        }
        serving.interrupt();
      }
      lobbyThread.interrupt(); survivalThread.interrupt();
    }
  }
  private static void failedSwitchKeepsCurrentBackend() throws Exception {
    Path secret = Files.createTempFile("conduit-forwarding", ".secret"); Files.writeString(secret, "wire-secret");
    try (ServerSocket lobby = new ServerSocket(0)) {
      Thread lobbyThread = Thread.startVirtualThread(() -> serveBackend(lobby, "Paper", (byte) 1, new AtomicReference<>()));
      ConduitConfiguration configuration = new ConduitConfiguration(new InetSocketAddress("127.0.0.1", reservePort()), 4096,
          ForwardingMode.MODERN, Optional.of(secret),
          List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", lobby.getLocalPort())),
              new BackendServer("survival", new InetSocketAddress("127.0.0.1", 1))),
          List.of("lobby"), List.of("lobby"));
      try (MinecraftProxy proxy = new MinecraftProxy(configuration)) {
        int proxyPort = proxy.port();
        Thread serving = Thread.startVirtualThread(() -> { try { proxy.serve(); } catch (Exception ignored) { } });
        try (Socket client = new Socket("127.0.0.1", proxyPort)) {
          MinecraftFrames.write(client.getOutputStream(), new byte[] {0, (byte) 0xFD, 5, 5, 'l', 'o', 'c', 'a', 'l', 0x63, (byte) 0xDD, 2});
          MinecraftFrames.write(client.getOutputStream(), loginStart());
          MinecraftFrames.read(client.getInputStream(), 4096);
          MinecraftFrames.read(client.getInputStream(), 4096);
          MinecraftFrames.read(client.getInputStream(), 4096);
          MinecraftFrames.write(client.getOutputStream(), new byte[] {2});
          MinecraftFrames.write(client.getOutputStream(), chatCommand("server survival"));
          byte[] message = readUntilPacket(client, 0x69);
          require(PlayPackets.packetId(message) == 0x69, "system chat on failed switch");
        }
        serving.interrupt();
      }
      lobbyThread.interrupt();
    }
  }
  private static void serveBackend(ServerSocket listener, String brand, byte marker, AtomicReference<String> uuidSink) {
    while (!Thread.currentThread().isInterrupted()) {
      try (Socket socket = listener.accept()) {
        MinecraftFrames.read(socket.getInputStream(), 4096);
        MinecraftFrames.read(socket.getInputStream(), 4096);
        MinecraftFrames.write(socket.getOutputStream(), modernRequest(9, 1));
        MinecraftFrames.read(socket.getInputStream(), 4096);
        MinecraftFrames.write(socket.getOutputStream(), new byte[] {2});
        MinecraftFrames.write(socket.getOutputStream(), new PluginMessage("minecraft:brand", PluginMessage.brandPayload(brand)).encode(0));
        MinecraftFrames.write(socket.getOutputStream(), new byte[] {2});
        while (!Thread.currentThread().isInterrupted()) {
          try { MinecraftFrames.read(socket.getInputStream(), 4096); }
          catch (Exception closed) { break; }
        }
      } catch (Exception ignored) { break; }
    }
  }
  private static byte[] chatCommand(String command) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, 4);
      MinecraftOutput.string(output, command);
      output.writeLong(0); output.writeLong(0); MinecraftOutput.varInt(output, 0);
    }
    return bytes.toByteArray();
  }
  private static byte[] modernRequest(int messageId, int version) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, 4); MinecraftOutput.varInt(output, messageId); MinecraftOutput.string(output, "velocity:player_info"); output.write(version);
    }
    return bytes.toByteArray();
  }
  private static byte[] loginStart() { return new byte[] {0, 5, 'p', 'l', 'a', 'y', 'r', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}; }
  private static int reservePort() throws Exception { try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
  private static void commandGraphMergeKeepsIndexesValid() throws Exception {
    byte[] merged = gg.tame.conduit.command.CommandGraphs.proxyOnly(ProtocolDefinition.forVersion(765), List.of("lobby", "survival"));
    require(PlayPackets.packetId(merged) == 0x11, "declare commands packet id");
    gg.tame.conduit.command.CommandGraph graph = gg.tame.conduit.command.CommandGraph.decode(java.util.Arrays.copyOfRange(merged, 1, merged.length));
    require(graph.nodes().size() >= 4, "proxy command nodes");
    byte[] again = gg.tame.conduit.command.CommandGraphs.mergeProxyCommands(ProtocolDefinition.forVersion(765), merged, List.of("lobby"));
    gg.tame.conduit.command.CommandGraph.decode(java.util.Arrays.copyOfRange(again, 1, again.length));
  }
  private static byte[] readUntilPacket(Socket client, int packetId) throws Exception {
    for (int attempt = 0; attempt < 8; attempt++) {
      byte[] packet = MinecraftFrames.read(client.getInputStream(), 4096);
      if (PlayPackets.packetId(packet) == packetId) return packet;
    }
    throw new AssertionError("did not receive packet id 0x" + Integer.toHexString(packetId));
  }
  private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
  private static final class RecordingSource implements CommandSource {
    private final String backend;
    private final boolean permitted;
    private final List<String> messages = new ArrayList<>();
    private RecordingSource(String backend, boolean permitted) { this.backend = backend; this.permitted = permitted; }
    @Override public String username() { return "playr"; }
    @Override public boolean hasPermission(String permission) { return permitted; }
    @Override public void sendMessage(String message) { messages.add(message); }
    @Override public String currentBackend() { return backend; }
  }
}
