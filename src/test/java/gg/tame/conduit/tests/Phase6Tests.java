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
    legacyTabCompleteKeepsSession();
    legacySwitchWaitsForJoinGame();
    chatCommand119IsConduits();
    Phase7Tests.run();
  }
  /**
   * 1.19 sends a command in its own Chat Command packet, without the slash. A real 1.19.4 client's
   * /conduit and /server reached the backend, which answered "Unknown or incomplete command": the
   * table inherited 1.13's legacy chat and Conduit waited for a slash. The command must be Conduit's,
   * answered in a System Chat the client reads, and never reach the backend.
   */
  private static void chatCommand119IsConduits() throws Exception {
    ProtocolDefinition v1194 = ProtocolDefinition.forVersion(762);
    int commandId = v1194.id(ConnectionState.PLAY, gg.tame.conduit.protocol.PacketDirection.CLIENT_TO_SERVER, gg.tame.conduit.protocol.PacketKind.PLAY_CHAT_COMMAND);
    int systemChatId = v1194.id(ConnectionState.PLAY, gg.tame.conduit.protocol.PacketDirection.SERVER_TO_CLIENT, gg.tame.conduit.protocol.PacketKind.PLAY_SYSTEM_CHAT);
    require(commandId == 0x04 && systemChatId == 0x64, "1.19.4 Chat Command 0x04 and System Chat 0x64");
    require(!v1194.capabilities().legacyPlayChat(), "1.19.4 is not legacy chat");
    try (ServerSocket backendListener = new ServerSocket(0)) {
      AtomicReference<String> forwarded = new AtomicReference<>("");
      Thread backend = Thread.startVirtualThread(() -> {
        try (Socket socket = backendListener.accept()) {
          MinecraftFrames.read(socket.getInputStream(), 4096);
          byte[] loginStart = MinecraftFrames.read(socket.getInputStream(), 4096);
          require(gg.tame.conduit.login.LoginStart.decode(PlayPackets.body(loginStart), v1194).username().equals("playr"), "1.19.4 Login Start reaches the backend in its layout");
          ByteArrayOutputStream success = new ByteArrayOutputStream();
          try (DataOutputStream output = new DataOutputStream(success)) {
            MinecraftOutput.varInt(output, 2); output.writeLong(0); output.writeLong(0);
            MinecraftOutput.string(output, "playr"); MinecraftOutput.varInt(output, 0);
          }
          MinecraftFrames.write(socket.getOutputStream(), success.toByteArray());
          while (true) {
            byte[] packet = MinecraftFrames.read(socket.getInputStream(), 4096);
            if (PlayPackets.packetId(packet) == commandId) forwarded.set("backend got a Chat Command");
          }
        } catch (Exception ignored) { }
      });
      ConduitConfiguration configuration = new ConduitConfiguration(new InetSocketAddress("127.0.0.1", reservePort()), 4096,
          ForwardingMode.NONE, Optional.empty(),
          List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", backendListener.getLocalPort()))),
          List.of("lobby"), List.of("lobby"));
      try (MinecraftProxy proxy = new MinecraftProxy(configuration)) {
        Thread serving = Thread.startVirtualThread(() -> { try { proxy.serve(); } catch (Exception ignored) { } });
        try (Socket client = new Socket("127.0.0.1", proxy.port())) {
          client.setSoTimeout(10_000);
          MinecraftFrames.write(client.getOutputStream(), new Handshake(762, "localhost", 25565, 2).encode());
          ByteArrayOutputStream loginStart = new ByteArrayOutputStream();
          try (DataOutputStream output = new DataOutputStream(loginStart)) {
            MinecraftOutput.varInt(output, 0); MinecraftOutput.string(output, "playr");
            output.writeBoolean(true); output.writeLong(0); output.writeLong(0);
          }
          MinecraftFrames.write(client.getOutputStream(), loginStart.toByteArray());
          require(PlayPackets.packetId(MinecraftFrames.read(client.getInputStream(), 4096)) == 2, "1.19.4 Login Success");
          ByteArrayOutputStream command = new ByteArrayOutputStream();
          try (DataOutputStream output = new DataOutputStream(command)) {
            MinecraftOutput.varInt(output, commandId); MinecraftOutput.string(output, "conduit");
            output.writeLong(0); output.writeLong(0); MinecraftOutput.varInt(output, 0);
            MinecraftOutput.varInt(output, 0); output.write(new byte[3]);
          }
          MinecraftFrames.write(client.getOutputStream(), command.toByteArray());
          byte[] reply = readUntilPacket(client, systemChatId);
          var input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(PlayPackets.body(reply)));
          require(MinecraftInput.string(input, 32767).contains("Conduit"), "/conduit is answered by Conduit");
          require(!input.readBoolean() && input.available() == 0, "1.19.4 System Chat ends with its action-bar flag");
          Thread.sleep(300);
        }
        require(forwarded.get().isEmpty(), "the command must not reach the backend: " + forwarded.get());
        serving.interrupt();
      }
      backend.interrupt();
    }
    // 1.19 alone names a chat type by id at the end of System Chat, where 1 is "system".
    var body119 = new java.io.DataInputStream(new java.io.ByteArrayInputStream(
        PlayPackets.body(PlayPackets.systemChat(ProtocolDefinition.forVersion(759), "hello"))));
    MinecraftInput.string(body119, 32767);
    require(MinecraftInput.varInt(body119) == 1 && body119.available() == 0, "1.19 System Chat carries chat type 1");
    for (int protocol : new int[] {759, 760, 761, 762}) {
      require(!ProtocolDefinition.forVersion(protocol).capabilities().legacyPlayChat(), protocol + " has 1.19's command chat");
    }
    require(ProtocolDefinition.forVersion(758).capabilities().legacyPlayChat(), "1.18.2 keeps legacy chat");
  }
  /**
   * A backend with no Configuration phase decodes Login until it sends Join Game. Switching a real
   * 1.8.9 client DIRECT between two 1.8.9 servers, Conduit replayed the client's settings as soon as
   * Login Success arrived, and both servers dropped it with "Bad packet id 21". The survival mock
   * here leaves a gap between the two packets and fails if anything reaches it inside that gap,
   * while the client keeps moving the whole time; the settings must still arrive after Join Game.
   */
  private static void legacySwitchWaitsForJoinGame() throws Exception {
    byte[] settings = legacySettings();
    try (ServerSocket lobby = new ServerSocket(0); ServerSocket survival = new ServerSocket(0)) {
      Thread lobbyThread = Thread.startVirtualThread(() -> {
        try (Socket socket = lobby.accept()) {
          MinecraftFrames.read(socket.getInputStream(), 4096);
          MinecraftFrames.read(socket.getInputStream(), 4096);
          MinecraftFrames.write(socket.getOutputStream(), legacyPacket(2, "00000000-0000-0000-0000-000000000000", "playr"));
          MinecraftFrames.write(socket.getOutputStream(), legacyJoinGame(1));
          socket.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
        } catch (Exception ignored) { }
      });
      AtomicReference<String> early = new AtomicReference<>("");
      java.util.concurrent.CountDownLatch replayed = new java.util.concurrent.CountDownLatch(1);
      Thread survivalThread = Thread.startVirtualThread(() -> {
        try (Socket socket = survival.accept()) {
          MinecraftFrames.read(socket.getInputStream(), 4096);
          MinecraftFrames.read(socket.getInputStream(), 4096);
          MinecraftFrames.write(socket.getOutputStream(), legacyPacket(2, "00000000-0000-0000-0000-000000000000", "playr"));
          socket.setSoTimeout(700);
          try {
            byte[] tooSoon = MinecraftFrames.read(socket.getInputStream(), 4096);
            early.set("packet 0x" + Integer.toHexString(PlayPackets.packetId(tooSoon)) + " before Join Game");
          } catch (java.net.SocketTimeoutException quiet) { }
          socket.setSoTimeout(10_000);
          MinecraftFrames.write(socket.getOutputStream(), legacyJoinGame(2));
          while (true) {
            if (java.util.Arrays.equals(MinecraftFrames.read(socket.getInputStream(), 4096), settings)) replayed.countDown();
          }
        } catch (Exception ignored) { }
      });
      ConduitConfiguration configuration = new ConduitConfiguration(new InetSocketAddress("127.0.0.1", reservePort()), 4096,
          ForwardingMode.NONE, Optional.empty(),
          List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", lobby.getLocalPort())),
              new BackendServer("survival", new InetSocketAddress("127.0.0.1", survival.getLocalPort()))),
          List.of("lobby"), List.of("lobby"));
      try (MinecraftProxy proxy = new MinecraftProxy(configuration)) {
        Thread serving = Thread.startVirtualThread(() -> { try { proxy.serve(); } catch (Exception ignored) { } });
        try (Socket client = new Socket("127.0.0.1", proxy.port())) {
          client.setSoTimeout(10_000);
          MinecraftFrames.write(client.getOutputStream(), new Handshake(47, "localhost", 25565, 2).encode());
          MinecraftFrames.write(client.getOutputStream(), legacyPacket(0, "playr"));
          readUntilPacket(client, 0x01);
          MinecraftFrames.write(client.getOutputStream(), settings);
          MinecraftFrames.write(client.getOutputStream(), legacyPacket(0x01, "/server survival"));
          // A client on its way out of one world still sends a movement packet every tick.
          Thread moving = Thread.startVirtualThread(() -> {
            try {
              for (int tick = 0; tick < 40; tick++) {
                synchronized (client) { MinecraftFrames.write(client.getOutputStream(), new byte[] {0x03, 1}); }
                Thread.sleep(50);
              }
            } catch (Exception ignored) { }
          });
          boolean replayedInTime = replayed.await(10, java.util.concurrent.TimeUnit.SECONDS);
          moving.join();
          require(early.get().isEmpty(), "nothing may reach a backend before its Join Game: " + early.get());
          require(replayedInTime, "settings replayed to survival after its Join Game");
        }
        serving.interrupt();
      }
      lobbyThread.interrupt(); survivalThread.interrupt();
    }
  }
  private static byte[] legacySettings() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, 0x15); MinecraftOutput.string(output, "en_US");
      output.writeByte(8); output.writeByte(0); output.writeBoolean(true); output.writeByte(0x7F);
    }
    return bytes.toByteArray();
  }
  /** 1.8 Join Game: entity id, game mode, dimension, difficulty, max players, level type, reduced debug. */
  private static byte[] legacyJoinGame(int entityId) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, 0x01); output.writeInt(entityId); output.writeByte(0); output.writeByte(0);
      output.writeByte(1); output.writeByte(20); MinecraftOutput.string(output, "flat"); output.writeBoolean(false);
    }
    return bytes.toByteArray();
  }
  /**
   * A 1.8 client's Tab-Complete request has no transaction id and its reply no range. Read with the
   * 1.13 layout, the text's length prefix was taken for a transaction id and the string read ran off
   * the end of the packet, which ended a real 1.8.9 session on its first Tab press. Drives that
   * exchange through a live proxy: the replies must be in the 1.8 layout, a command name must keep
   * its slash, and a chat line sent afterwards must still reach the backend.
   */
  private static void legacyTabCompleteKeepsSession() throws Exception {
    try (ServerSocket backendListener = new ServerSocket(0)) {
      AtomicReference<String> chat = new AtomicReference<>();
      Thread backend = Thread.startVirtualThread(() -> {
        try (Socket socket = backendListener.accept()) {
          MinecraftFrames.read(socket.getInputStream(), 4096);
          MinecraftFrames.read(socket.getInputStream(), 4096);
          MinecraftFrames.write(socket.getOutputStream(), legacyPacket(2, "00000000-0000-0000-0000-000000000000", "playr"));
          while (chat.get() == null) {
            byte[] packet = MinecraftFrames.read(socket.getInputStream(), 4096);
            // A command name reaches the backend, which answers with the commands it knows -- except
            // for "/g", whose reply never comes, as when a translator drops an empty one.
            if (PlayPackets.packetId(packet) == 0x14 && !new String(packet, java.nio.charset.StandardCharsets.UTF_8).contains("/g")) {
              MinecraftFrames.write(socket.getOutputStream(), legacyPacketWithCount(0x3A, "/seed"));
            }
            if (PlayPackets.packetId(packet) != 0x01) continue;
            try (var input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(packet))) {
              MinecraftInput.varInt(input);
              chat.set(MinecraftInput.string(input, 100));
            }
          }
          socket.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
        } catch (Exception ignored) { }
      });
      ConduitConfiguration configuration = new ConduitConfiguration(new InetSocketAddress("127.0.0.1", reservePort()), 4096,
          ForwardingMode.NONE, Optional.empty(),
          List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", backendListener.getLocalPort()))),
          List.of("lobby"), List.of("lobby"));
      try (MinecraftProxy proxy = new MinecraftProxy(configuration)) {
        Thread serving = Thread.startVirtualThread(() -> { try { proxy.serve(); } catch (Exception ignored) { } });
        try (Socket client = new Socket("127.0.0.1", proxy.port())) {
          client.setSoTimeout(10_000);
          MinecraftFrames.write(client.getOutputStream(), new Handshake(47, "localhost", 25565, 2).encode());
          MinecraftFrames.write(client.getOutputStream(), legacyPacket(0, "playr"));
          require(PlayPackets.packetId(MinecraftFrames.read(client.getInputStream(), 4096)) == 2, "1.8 Login Success");
          // Tab after "/server ", sent the way a 1.8 client sends it: text, then no looked-at block.
          MinecraftFrames.write(client.getOutputStream(), legacyTabRequest("/server "));
          require(java.util.Arrays.equals(readUntilPacket(client, 0x3A), legacyPacketWithCount(0x3A, "lobby")), "1.8 reply lists servers");
          MinecraftFrames.write(client.getOutputStream(), legacyTabRequest("/server"));
          require(java.util.Arrays.equals(readUntilPacket(client, 0x3A), legacyPacketWithCount(0x3A, "/server")), "command name keeps its slash");
          // A request whose reply never arrives must leave nothing behind for the next reply: Conduit has
          // /glist and /gkick, which must not turn up in its own list of servers.
          MinecraftFrames.write(client.getOutputStream(), legacyTabRequest("/g"));
          MinecraftFrames.write(client.getOutputStream(), legacyTabRequest("/server "));
          require(java.util.Arrays.equals(readUntilPacket(client, 0x3A), legacyPacketWithCount(0x3A, "lobby")), "no stale names in Conduit's own reply");
          // A partial name is the backend's to answer; Conduit's own commands are added to its reply.
          MinecraftFrames.write(client.getOutputStream(), legacyTabRequest("/se"));
          List<String> names = PlayPackets.legacyTabMatches(readUntilPacket(client, 0x3A));
          require(names.size() == 3 && names.containsAll(List.of("/seed", "/send", "/server")), "backend and Conduit command names: " + names);
          MinecraftFrames.write(client.getOutputStream(), legacyPacket(0x01, "still here"));
          backend.join(10_000);
        }
        require("still here".equals(chat.get()), "session still carries chat after Tab: " + chat.get());
        serving.interrupt();
      }
    }
    // 1.12.2 appends a command-block flag and the looked-at block; 1.13+ keeps its transaction id.
    ByteArrayOutputStream request1122 = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(request1122)) {
      MinecraftOutput.varInt(output, 0x01); MinecraftOutput.string(output, "/server s"); output.writeBoolean(false); output.writeBoolean(false);
    }
    require(PlayPackets.tabRequest(ProtocolDefinition.forVersion(340), request1122.toByteArray()).text().equals("/server s"), "1.12.2 request text");
    ByteArrayOutputStream request1204 = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(request1204)) {
      MinecraftOutput.varInt(output, 0x0A); MinecraftOutput.varInt(output, 7); MinecraftOutput.string(output, "/server s");
    }
    PlayPackets.TabRequest modern = PlayPackets.tabRequest(ProtocolDefinition.forVersion(765), request1204.toByteArray());
    require(modern.transactionId() == 7 && modern.text().equals("/server s"), "1.20.4 request keeps its transaction id");
  }
  private static byte[] legacyTabRequest(String text) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, 0x14); MinecraftOutput.string(output, text); output.writeBoolean(false);
    }
    return bytes.toByteArray();
  }
  private static byte[] legacyPacket(int id, String... strings) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, id);
      for (String value : strings) MinecraftOutput.string(output, value);
    }
    return bytes.toByteArray();
  }
  private static byte[] legacyPacketWithCount(int id, String... strings) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, id);
      MinecraftOutput.varInt(output, strings.length);
      for (String value : strings) MinecraftOutput.string(output, value);
    }
    return bytes.toByteArray();
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
    Path config = TempFiles.file("conduit", ".toml");
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
    require(player.messages.stream().anyMatch(line -> line.contains("Unknown server: missing")), "unknown");
    core.dispatch(player, "/conduit");
    require(player.messages.stream().anyMatch(line -> line.startsWith("Conduit ")), "conduit version");
    require(player.messages.stream().anyMatch(line -> line.equals("Current server: lobby")), "conduit current server");
    require(core.tabComplete(player, "/server s").equals(List.of("survival")), "server tab filter");
    require(core.dispatch(player, "/lobby"), "slash-server alias");
    core.dispatch(player, "/conduit servers");
    require(player.messages.stream().anyMatch(line -> line.equals("Conduit Servers")), "conduit servers");
    require(player.messages.stream().anyMatch(line -> line.equals("● lobby")), "conduit servers current");
    require(player.messages.stream().anyMatch(line -> line.contains("Online") || line.contains("Unknown")), "conduit servers state");
    core.dispatch(player, "/conduit help");
    require(player.messages.stream().anyMatch(line -> line.contains("/server")), "conduit help");
  }
  private static void serverNameMatching() throws Exception {
    Path config = TempFiles.file("conduit", ".toml");
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
    Path secret = TempFiles.file("conduit-forwarding", ".secret"); Files.writeString(secret, "wire-secret");
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
          client.setSoTimeout(15_000);
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
          // A player sends the next /server once the first has landed. Sent any sooner, it is refused
          // as a switch already in progress. This test used to be rescued from that by a stale switch
          // deadline that dropped the silent survival backend and fell back to lobby, whose Start
          // Configuration it then took for its own.
          readUntilText(client, "Connected to");
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
    Path secret = TempFiles.file("conduit-forwarding", ".secret"); Files.writeString(secret, "wire-secret");
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
  private static void readUntilText(Socket client, String text) throws Exception {
    for (int attempt = 0; attempt < 16; attempt++) {
      byte[] packet = MinecraftFrames.read(client.getInputStream(), 4096);
      if (new String(packet, java.nio.charset.StandardCharsets.UTF_8).contains(text)) return;
    }
    throw new AssertionError("never received \"" + text + "\"");
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
