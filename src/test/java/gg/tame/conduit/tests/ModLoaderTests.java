package gg.tame.conduit.tests;

import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.config.ForwardingMode;
import gg.tame.conduit.forwarding.NoneForwarder;
import gg.tame.conduit.login.BackendLoginPipeline;
import gg.tame.conduit.login.LoginPluginRequest;
import gg.tame.conduit.login.LoginStart;
import gg.tame.conduit.login.PlayerProfile;
import gg.tame.conduit.modded.ChannelDetector;
import gg.tame.conduit.modded.FmlAddressMarkers;
import gg.tame.conduit.modded.HandshakeClassifier;
import gg.tame.conduit.modded.FmlHandshakeReset;
import gg.tame.conduit.modded.ModLoaderFamily;
import gg.tame.conduit.modded.PluginPayloadValidator;
import gg.tame.conduit.modded.RegisteredChannels;
import gg.tame.conduit.network.MinecraftProxy;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PluginMessage;
import gg.tame.conduit.protocol.ProtocolDefinition;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Forge and NeoForge wire behaviour.
 *
 * <p>Most of this is scripted: a socket client and socket backends through a real
 * {@link MinecraftProxy}, and the real {@link BackendLoginPipeline} on packets built from the
 * 1.20.4 table. Two things are not, and they are the ones that corrected the rest — a real
 * NeoForge 20.2.93 client and a real NeoForge 20.2.93 server, captured on both sides of Conduit:
 *
 * <ul>
 *   <li>the client's handshake host is {@code "127.0.0.1\0FML3"} — leading NUL, token, nothing
 *       after it. The doubly-delimited {@code "\0FML3\0"} the wiki documents is what Conduit
 *       matched, so every one of those handshakes was counted malformed and dropped in silence;
 *   <li>the server's login handshake is pipelined: Login Plugin Requests {@code 0x00} through
 *       {@code 0x14} on {@code fml:loginwrapper}, all inside one second, none of them waiting for
 *       a reply.
 * </ul>
 *
 * <p>{@code \0FORGE\0} was never seen on any wire. It is carried on documentation alone.
 */
public final class ModLoaderTests {
  private static final int PROTOCOL = 765;
  private static final java.nio.charset.Charset UTF8 = java.nio.charset.StandardCharsets.UTF_8;

  private ModLoaderTests() {}

  public static void main(String[] a) throws Exception { run(); }

  public static void run() throws Exception {
    markersAreNeverInvented();
    legacyAndModernChannels();
    modernForgeTokenReachesTheBackend();
    loginQueriesGoToTheClient();
    configurationPayloadsSurviveASwitch();
    registeredChannelsCanBeReplayed();
    fml1HandshakeReset();
    capturedNeoForgeLogin();
    knownPacksOfAModdedClient();
    System.out.println("ModLoaderTests passed.");
  }

  /** Every documented token parses, round-trips, and is carried on unchanged. */
  private static void markersAreNeverInvented() {
    var forge = FmlAddressMarkers.parse("play.example.com" + FmlAddressMarkers.FORGE);
    require(forge.marker() == FmlAddressMarkers.MarkerKind.FORGE, "1.20.2+ FORGE token parses");
    require(forge.cleanHost().equals("play.example.com"), "the token is stripped off the host");
    // The token alone does not say Forge or NeoForge; brand evidence decides, and only upwards.
    require(forge.family() == ModLoaderFamily.FORGE, "FORGE token classifies as Forge");
    require(FmlAddressMarkers.append("play.example.com", FmlAddressMarkers.MarkerKind.FORGE)
        .equals("play.example.com" + FmlAddressMarkers.FORGE), "FORGE token round-trips");

    // No token names NeoForge: its first release is inside the FML2/FML3 range and sends what a
    // Forge client of that version sends. Every token classifies Forge; the brand promotes.
    for (var token : List.of(FmlAddressMarkers.FML1, FmlAddressMarkers.FML2, FmlAddressMarkers.FML3, FmlAddressMarkers.FORGE)) {
      require(FmlAddressMarkers.parse("h" + token).family() == ModLoaderFamily.FORGE, "token " + printable(token) + " is Forge");
    }
    require(ModLoaderFamily.parse("fml3") == ModLoaderFamily.FORGE, "fml3 names Forge in config too");

    // Captured off the wire from a real NeoForge 20.2.93 client (Minecraft 1.20.2, protocol 764)
    // connecting to Conduit: host "127.0.0.1\0FML3". Leading NUL, token, nothing after it. The
    // doubly-delimited form the wiki documents is what Conduit matched, so every such handshake
    // was counted malformed and the socket closed with no reply.
    var observed = FmlAddressMarkers.parse("127.0.0.1\0FML3");
    require(observed.marker() == FmlAddressMarkers.MarkerKind.FML3, "a real NeoForge client's token parses");
    require(observed.cleanHost().equals("127.0.0.1"), "and its address survives the split");
    require(FmlAddressMarkers.parse("h\0FML3\0").marker() == FmlAddressMarkers.MarkerKind.FML3,
        "the trailing NUL stays optional, not forbidden");

    HandshakeClassifier neo = new HandshakeClassifier();
    neo.observeHandshakeHost("h" + FmlAddressMarkers.FORGE);
    require(neo.family() == ModLoaderFamily.FORGE, "a 1.20.2+ token alone is Forge");
    neo.observeBrand("neoforge");
    require(neo.family() == ModLoaderFamily.NEOFORGE, "the brand promotes it to NeoForge");

    for (var kind : FmlAddressMarkers.MarkerKind.values()) {
      require(FmlAddressMarkers.markerFor(ModLoaderFamily.NEOFORGE, kind) == kind
          && FmlAddressMarkers.markerFor(ModLoaderFamily.FORGE, kind) == kind,
          "a backend handshake carries the client's own token and never one Conduit chose");
    }
    try {
      FmlAddressMarkers.parse("host\0NOTATOKEN\0");
      throw new AssertionError("an unknown NUL suffix must still be refused");
    } catch (IllegalArgumentException expected) { /* ok */ }
  }

  private static void legacyAndModernChannels() {
    // 1.7-1.12 has no namespaces: FML1 runs its whole handshake on these in the Play phase.
    require(ChannelDetector.classify("FML|HS") == ChannelDetector.ChannelClass.FORGE, "FML|HS is Forge");
    require(ChannelDetector.classify("FML|MP") == ChannelDetector.ChannelClass.FORGE, "FML|MP is Forge");
    require(ChannelDetector.classify("FORGE") == ChannelDetector.ChannelClass.FORGE, "FORGE is Forge");
    require(ChannelDetector.classify("fml:loginwrapper") == ChannelDetector.ChannelClass.FORGE, "fml:loginwrapper is Forge");
    require(ChannelDetector.classify("neoforge:register") == ChannelDetector.ChannelClass.NEOFORGE, "neoforge: is NeoForge");
    require(ChannelDetector.classify("minecraft:register") == ChannelDetector.ChannelClass.VANILLA,
        "minecraft:register is a vanilla channel whatever it carries");

    HandshakeClassifier legacy = new HandshakeClassifier();
    legacy.observeHandshakeHost("h" + FmlAddressMarkers.FML1);
    legacy.observeChannel("FML|HS");
    require(legacy.family() == ModLoaderFamily.FORGE, "a 1.12 Forge client is Forge from either signal");
  }

  /**
   * A 1.20.2+ Forge/NeoForge client joins, and the backend is handed the token unchanged.
   *
   * <p>Both halves matter. Conduit used to drop the connection at the handshake; and a backend that
   * is itself modded refuses a client whose handshake arrives without the token.
   */
  private static void modernForgeTokenReachesTheBackend() throws Exception {
    String host = "local" + FmlAddressMarkers.FORGE;
    AtomicReference<String> seenHost = new AtomicReference<>();
    try (ServerSocket backendListener = new ServerSocket(0)) {
      AtomicReference<Throwable> backendFailure = new AtomicReference<>();
      Thread backend = Thread.startVirtualThread(() -> {
        try (Socket socket = backendListener.accept()) {
          InputStream in = socket.getInputStream();
          OutputStream out = socket.getOutputStream();
          seenHost.set(Handshake.decode(MinecraftFrames.read(in, 4096)).requestedHost());
          MinecraftFrames.read(in, 4096);
          MinecraftFrames.write(out, loginSuccess());
          MinecraftFrames.read(in, 4096);
        } catch (Throwable failure) {
          backendFailure.set(failure);
        }
      });
      ConduitConfiguration configuration = new ConduitConfiguration(new InetSocketAddress("127.0.0.1", reservePort()), 4096,
          ForwardingMode.NONE, Optional.empty(),
          List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", backendListener.getLocalPort()))),
          List.of("lobby"), List.of());
      try (MinecraftProxy proxy = new MinecraftProxy(configuration)) {
        Thread serving = Thread.startVirtualThread(() -> { try { proxy.serve(); } catch (Exception ignored) { } });
        // The drop a 1.20.2+ client used to hit: an unrecognised token is a malformed handshake and
        // the socket closes with nothing written back. Still the right answer for a token nobody
        // sends; it was the wrong answer for the one Forge and NeoForge send now.
        try (Socket refused = new Socket("127.0.0.1", proxy.port())) {
          refused.setSoTimeout(5_000);
          MinecraftFrames.write(refused.getOutputStream(), new Handshake(PROTOCOL, "local\0NOTATOKEN\0", 25565, 2).encode());
          require(refused.getInputStream().read() == -1, "an unknown handshake token is refused with no reply");
        }
        try (Socket client = new Socket("127.0.0.1", proxy.port())) {
          client.setSoTimeout(5_000);
          MinecraftFrames.write(client.getOutputStream(), new Handshake(PROTOCOL, host, 25565, 2).encode());
          MinecraftFrames.write(client.getOutputStream(), loginStart());
          byte[] first = MinecraftFrames.read(client.getInputStream(), 4096);
          require(first[0] == 0x02, "a client with the 1.20.2+ Forge token is logged in, not dropped");
          backend.join(5_000);
        }
        serving.interrupt();
      }
      if (backendFailure.get() != null) throw new AssertionError("mock backend: " + backendFailure.get(), backendFailure.get());
    }
    require(host.equals(seenHost.get()),
        "the backend handshake keeps the client's token (got " + printable(seenHost.get()) + ")");
  }

  /**
   * An unknown login query is the client's to answer. Forge 1.13-1.20.1 asks on
   * {@code fml:loginwrapper} and a proxy that answers or drops it ends the join.
   */
  private static void loginQueriesGoToTheClient() throws Exception {
    ProtocolDefinition protocol = ProtocolDefinition.forVersion(PROTOCOL);
    byte[] query = loginPluginRequest(protocol, 7, "fml:loginwrapper", new byte[] {1, 2, 3});

    // Off by default: nothing but a connection whose client is still in LOGIN may enable it.
    expectIO(() -> pipeline().onBackendPacket(query, 4096), "a query relayed with no client to answer it");

    BackendLoginPipeline pipeline = pipeline();
    pipeline.allowClientLoginQueries();
    require(pipeline.onBackendPacket(query, 4096) == null, "Conduit writes no answer of its own");
    require(pipeline.shouldForward(), "the query goes on to the client");
    require(pipeline.awaitingLoginQuery(), "the response is owed to the backend");

    byte[] answer = loginPluginResponse(protocol, 7, new byte[] {9, 9});
    require(Arrays.equals(pipeline.clientLoginQueryResponse(answer), answer), "the client's bytes reach the backend unchanged");
    require(!pipeline.awaitingLoginQuery(), "the query is settled");

    // The correlation id is the whole point of the query: answering the wrong one is a desync.
    BackendLoginPipeline mismatched = pipeline();
    mismatched.allowClientLoginQueries();
    mismatched.onBackendPacket(query, 4096);
    expectIO(() -> mismatched.clientLoginQueryResponse(loginPluginResponse(protocol, 8, new byte[0])), "a response to another query accepted");
    expectIO(() -> pipeline().clientLoginQueryResponse(loginPluginResponse(protocol, 7, new byte[0])), "a response with no query outstanding accepted");

    BackendLoginPipeline wrongPacket = pipeline();
    wrongPacket.allowClientLoginQueries();
    wrongPacket.onBackendPacket(query, 4096);
    expectIO(() -> wrongPacket.clientLoginQueryResponse(new byte[] {0x03}), "Login Acknowledged accepted as a query response");

    // A backend that asks twice is answered twice, each against its own id.
    BackendLoginPipeline repeated = pipeline();
    repeated.allowClientLoginQueries();
    repeated.onBackendPacket(loginPluginRequest(protocol, 1, "fml:loginwrapper", new byte[0]), 4096);
    repeated.clientLoginQueryResponse(loginPluginResponse(protocol, 1, new byte[0]));
    repeated.onBackendPacket(loginPluginRequest(protocol, 2, "neoforge:something", new byte[0]), 4096);
    require(repeated.awaitingLoginQuery(), "the second query is outstanding too");
    repeated.clientLoginQueryResponse(loginPluginResponse(protocol, 2, new byte[0]));
    require(!repeated.awaitingLoginQuery(), "both queries settled");

    pipelinedQueries(protocol);
    hostileLoginQueries(protocol);
  }

  /**
   * The exchange is pipelined, and a captured run says by how much: a real NeoForge 20.2.93 server
   * sent Login Plugin Requests 0x00 through 0x14 in under a second without reading one reply.
   * Answers may then come back in any order.
   */
  private static void pipelinedQueries(ProtocolDefinition protocol) throws Exception {
    BackendLoginPipeline pipeline = pipeline();
    pipeline.allowClientLoginQueries();
    for (int id = 0; id <= 0x14; id++) {
      require(pipeline.onBackendPacket(loginPluginRequest(protocol, id, "fml:loginwrapper", new byte[] {(byte) id}), 65536) == null,
          "query " + id + " is forwarded, not answered");
      require(pipeline.shouldForward(), "query " + id + " goes to the client");
    }
    require(pipeline.outstandingLoginQueries() == 0x15, "all 21 queries stay outstanding at once");
    for (int id = 0x14; id >= 0; id--) {
      pipeline.clientLoginQueryResponse(loginPluginResponse(protocol, id, new byte[0]));
    }
    require(!pipeline.awaitingLoginQuery(), "answered in reverse order, every one settled");
  }

  /** The backend and the client are both untrusted here; neither may make Conduit grow unbounded. */
  private static void hostileLoginQueries(ProtocolDefinition protocol) throws Exception {
    BackendLoginPipeline flood = pipeline();
    flood.allowClientLoginQueries();
    for (int id = 0; id < BackendLoginPipeline.MAX_PENDING_QUERIES; id++) {
      flood.onBackendPacket(loginPluginRequest(protocol, id, "fml:loginwrapper", new byte[0]), 65536);
    }
    expectIO(() -> flood.onBackendPacket(loginPluginRequest(protocol, 9999, "fml:loginwrapper", new byte[0]), 65536),
        "a backend queried without bound");

    // The payload is length-checked against the configured frame limit before it is held.
    BackendLoginPipeline bounded = pipeline();
    bounded.allowClientLoginQueries();
    expectIO(() -> bounded.onBackendPacket(loginPluginRequest(protocol, 1, "fml:loginwrapper", new byte[4096]), 64),
        "an oversized login query payload accepted");

    // A client answering a query nobody asked, or answering twice, is a desync either way.
    BackendLoginPipeline once = pipeline();
    once.allowClientLoginQueries();
    once.onBackendPacket(loginPluginRequest(protocol, 3, "fml:loginwrapper", new byte[0]), 65536);
    once.clientLoginQueryResponse(loginPluginResponse(protocol, 3, new byte[0]));
    expectIO(() -> once.clientLoginQueryResponse(loginPluginResponse(protocol, 3, new byte[0])), "a query answered twice");

    // An FML token alone does not make the client modded; a client that then behaves as vanilla
    // stays classified by its evidence, and the token is still what the backend is told.
    HandshakeClassifier claimsForge = new HandshakeClassifier();
    claimsForge.observeHandshakeHost("h" + FmlAddressMarkers.FML3);
    claimsForge.observeChannel("minecraft:brand");
    claimsForge.observeBrand("vanilla");
    claimsForge.markLikelyVanilla();
    require(claimsForge.family() == ModLoaderFamily.FORGE,
        "a token is evidence Conduit does not discard because the client went quiet");
    require(claimsForge.marker() == FmlAddressMarkers.MarkerKind.FML3, "and the backend is told what the client sent");
  }

  /**
   * A 1.20.2+ loader negotiates in the Configuration phase with ordinary custom payloads, and that
   * is the only reason this range is carryable at all. Two backends, one switch, and every payload
   * checked byte for byte at the far end.
   */
  private static void configurationPayloadsSurviveASwitch() throws Exception {
    ProtocolDefinition p = ProtocolDefinition.forVersion(PROTOCOL);
    int configOut = p.id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_PLUGIN_MESSAGE);
    int configIn = p.id(ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_PLUGIN_MESSAGE);
    byte finishOut = (byte) p.id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_FINISH);
    byte finishIn = (byte) p.id(ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_FINISH);
    int joinGame = p.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN);
    int startConfiguration = p.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_START_CONFIGURATION);
    int chatCommand = p.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT_COMMAND);
    byte configurationAck = (byte) p.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CONFIGURATION_ACKNOWLEDGED);

    byte[] payloadA = "ModdedNetworkQueryPayload-from-lobby".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] payloadB = "ModdedNetworkQueryPayload-from-modded".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    // minecraft:register is a NUL-separated list of channel names, exactly as a mod loader sends it.
    byte[] registerPayload = "neoforge:main\0fml:handshake".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String clientHost = "local" + FmlAddressMarkers.FORGE;

    try (ServerSocket lobbyListener = new ServerSocket(0); ServerSocket moddedListener = new ServerSocket(0)) {
      Mock lobby = new Mock(lobbyListener, "lobby-brand", payloadA, configOut, finishOut, finishIn);
      Mock modded = new Mock(moddedListener, "modded-brand", payloadB, configOut, finishOut, finishIn);
      Thread lobbyThread = Thread.startVirtualThread(lobby);
      Thread moddedThread = Thread.startVirtualThread(modded);
      ConduitConfiguration configuration = new ConduitConfiguration(new InetSocketAddress("127.0.0.1", reservePort()), 8192,
          ForwardingMode.NONE, Optional.empty(),
          List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", lobbyListener.getLocalPort())),
              new BackendServer("modded", new InetSocketAddress("127.0.0.1", moddedListener.getLocalPort()))),
          List.of("lobby"), List.of());
      try (MinecraftProxy proxy = new MinecraftProxy(configuration)) {
        Thread serving = Thread.startVirtualThread(() -> { try { proxy.serve(); } catch (Exception ignored) { } });
        try (Socket client = new Socket("127.0.0.1", proxy.port())) {
          client.setSoTimeout(15_000);
          InputStream in = client.getInputStream();
          OutputStream out = client.getOutputStream();
          MinecraftFrames.write(out, new Handshake(PROTOCOL, clientHost, 25565, 2).encode());
          MinecraftFrames.write(out, loginStart());
          require(MinecraftFrames.read(in, 8192)[0] == 0x02, "Login Success");
          MinecraftFrames.write(out, new byte[] {0x03});

          List<byte[]> first = readConfiguration(in, finishOut);
          require(Arrays.equals(payload(first, configOut, "neoforge:register"), payloadA),
              "a neoforge: configuration payload reaches the client byte for byte");
          require(payload(first, configOut, "minecraft:brand") != null, "minecraft:brand reaches the client");

          MinecraftFrames.write(out, new PluginMessage("minecraft:register", registerPayload).encode(configIn));
          MinecraftFrames.write(out, new byte[] {finishIn});
          readUntil(in, joinGame);
          require(Arrays.equals(payload(lobby.received, configIn, "minecraft:register"), registerPayload),
              "the client's minecraft:register reaches the backend byte for byte");

          // /server modded. The command is dispatched by Conduit and never reaches a backend.
          MinecraftFrames.write(out, command(chatCommand, "server modded"));
          readUntil(in, startConfiguration);
          MinecraftFrames.write(out, new byte[] {configurationAck});

          List<byte[]> second = readConfiguration(in, finishOut);
          require(Arrays.equals(payload(second, configOut, "neoforge:register"), payloadB),
              "the new backend's configuration payload reaches the reconfiguring client");
          require(!Arrays.equals(payload(second, configOut, "neoforge:register"), payloadA),
              "the old backend's payload does not leak into the new configuration");
          MinecraftFrames.write(out, new byte[] {finishIn});
          readUntil(in, joinGame);

          require(modded.host.get() != null && modded.host.get().endsWith(FmlAddressMarkers.FORGE),
              "the switch handshake carries the client's token too (got " + printable(modded.host.get()) + ")");
          // Registration is per connection: the new backend was never told what the client announced
          // on the first one, so every mod and plugin channel went dark after a /server.
          byte[] replayed = payload(modded.received, configIn, "minecraft:register");
          require(replayed != null && Set.of(new String(replayed, UTF8).split("\0")).equals(Set.of("neoforge:main", "fml:handshake")),
              "the channels the client registered are announced to the new backend");
        }
        serving.interrupt();
      }
      lobbyThread.join(5_000);
      moddedThread.join(5_000);
      if (lobby.failure.get() != null) throw new AssertionError("lobby backend: " + lobby.failure.get(), lobby.failure.get());
      if (modded.failure.get() != null) throw new AssertionError("modded backend: " + modded.failure.get(), modded.failure.get());
    }
  }

  /**
   * The switch above shows the new backend is never told what the client registered. This is the
   * half of the fix that can live in {@code modded/}: remember the announcement, rebuild it for the
   * next backend. The session-side call sites are a diff.
   */
  private static void registeredChannelsCanBeReplayed() throws Exception {
    ProtocolDefinition modern = ProtocolDefinition.forVersion(PROTOCOL);
    RegisteredChannels registered = new RegisteredChannels();
    require(!registered.observe("minecraft:brand", new byte[0]), "brand is not a registration");
    require(registered.replay(modern, ConnectionState.CONFIGURATION).isEmpty(), "nothing to replay before anything is registered");

    require(registered.observe("minecraft:register", "neoforge:main\0fml:handshake\0plugin:chat".getBytes(UTF8)), "register observed");
    byte[] packet = registered.replay(modern, ConnectionState.CONFIGURATION).orElseThrow();
    require(packet[0] == (byte) modern.id(ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_PLUGIN_MESSAGE),
        "the replay is a Configuration plugin message in the client's direction");
    PluginMessage decoded = PluginMessage.decodeBody(Arrays.copyOfRange(packet, 1, packet.length), 8192);
    require(decoded.channel().equals("minecraft:register"), "on the channel the client used");
    require(Set.of(new String(decoded.data(), UTF8).split("\0")).equals(Set.of("neoforge:main", "fml:handshake", "plugin:chat")),
        "carrying every channel the client announced");

    registered.observe("minecraft:unregister", "fml:handshake".getBytes(UTF8));
    require(!registered.channels().contains("fml:handshake"), "an unregistered channel is forgotten");

    // Pre-1.13 names its register channel differently and has no Configuration phase.
    RegisteredChannels legacy = new RegisteredChannels();
    legacy.observe("REGISTER", "FML|HS\0FML".getBytes(UTF8));
    ProtocolDefinition old = ProtocolDefinition.forVersion(340);
    require(legacy.replay(old, ConnectionState.CONFIGURATION).isEmpty(), "1.12 has no Configuration phase to replay into");
    byte[] play = legacy.replay(old, ConnectionState.PLAY).orElseThrow();
    require(PluginMessage.decodeBody(Arrays.copyOfRange(play, 1, play.length), 8192).channel().equals("REGISTER"),
        "the client's own register channel name is echoed, not a version rule");

    // The client fills this set, so it is bounded.
    RegisteredChannels flood = new RegisteredChannels();
    StringBuilder names = new StringBuilder();
    for (int i = 0; i < RegisteredChannels.MAX_CHANNELS * 2; i++) names.append("mod").append(i).append('\0');
    flood.observe("minecraft:register", names.toString().getBytes(UTF8));
    require(flood.channels().size() == RegisteredChannels.MAX_CHANNELS, "a client cannot register without bound");

    // Hostile shapes: the payload is the client's, so none of it may throw or be stored whole.
    RegisteredChannels hostile = new RegisteredChannels();
    hostile.observe("minecraft:register", new byte[0]);
    hostile.observe("minecraft:register", "\0\0\0".getBytes(UTF8));
    hostile.observe("minecraft:register", "   ".getBytes(UTF8));
    hostile.observe("minecraft:register", new byte[] {(byte) 0xC3, (byte) 0x28, 0});
    hostile.observe("minecraft:register", ("x".repeat(100_000)).getBytes(UTF8));
    require(hostile.channels().isEmpty() || hostile.channels().size() <= RegisteredChannels.MAX_CHANNELS,
        "malformed register payloads never grow the set past its bound");
    for (String name : hostile.channels()) {
      require(!name.isBlank() && name.length() <= PluginPayloadValidator.MAX_CHANNEL_CHARS,
          "no blank or oversized channel is kept (" + name.length() + " chars)");
    }
    require(hostile.observe("minecraft:register", null), "a null payload is still a registration, and is survivable");
    // Repeated identical registers are idempotent, not cumulative.
    RegisteredChannels repeated = new RegisteredChannels();
    for (int i = 0; i < 50; i++) repeated.observe("minecraft:register", "a:b\0c:d".getBytes(UTF8));
    require(repeated.channels().size() == 2, "the same register fifty times is still two channels");
  }

  /** FML1's reset packet. Authoring only: no Forge client or server was in the loop. */
  private static void fml1HandshakeReset() throws Exception {
    ProtocolDefinition p1122 = ProtocolDefinition.forVersion(340);
    byte[] packet = FmlHandshakeReset.forSwitch(p1122, FmlAddressMarkers.MarkerKind.FML1).orElseThrow();
    require(packet[0] == (byte) p1122.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLUGIN_MESSAGE),
        "a clientbound Play plugin message");
    PluginMessage decoded = PluginMessage.decodeBody(Arrays.copyOfRange(packet, 1, packet.length), 8192);
    require(decoded.channel().equals("FML|HS"), "on FML|HS");
    require(decoded.data().length == 1 && decoded.data()[0] == -2, "discriminator -2, HandshakeReset");

    // 1.7 lengths the payload with a short; 1.8 dropped it. PluginMessage only writes the 1.8 form.
    byte[] legacy = FmlHandshakeReset.forSwitch(ProtocolDefinition.forVersion(5), FmlAddressMarkers.MarkerKind.FML1).orElseThrow();
    require(legacy[legacy.length - 1] == -2 && legacy[legacy.length - 2] == 1 && legacy[legacy.length - 3] == 0,
        "1.7 carries a short length of 1 before the discriminator");

    for (var marker : FmlAddressMarkers.MarkerKind.values()) {
      if (marker == FmlAddressMarkers.MarkerKind.FML1) continue;
      require(FmlHandshakeReset.forSwitch(p1122, marker).isEmpty(),
          "only FML1 has a handshake to reset (" + marker + ")");
    }
  }

  /** A backend that configures with one modded payload and then plays. */
  private static final class Mock implements Runnable {
    final ServerSocket listener;
    final String brand;
    final byte[] payload;
    final int configOut;
    final byte finishOut;
    final byte finishIn;
    final List<byte[]> received = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    final AtomicReference<String> host = new AtomicReference<>();
    final AtomicReference<Throwable> failure = new AtomicReference<>();

    Mock(ServerSocket listener, String brand, byte[] payload, int configOut, byte finishOut, byte finishIn) {
      this.listener = listener; this.brand = brand; this.payload = payload;
      this.configOut = configOut; this.finishOut = finishOut; this.finishIn = finishIn;
    }

    @Override public void run() {
      try (Socket socket = listener.accept()) {
        socket.setSoTimeout(15_000);
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();
        host.set(Handshake.decode(MinecraftFrames.read(in, 8192)).requestedHost());
        MinecraftFrames.read(in, 8192);
        MinecraftFrames.write(out, loginSuccess());
        MinecraftFrames.read(in, 8192);
        MinecraftFrames.write(out, new PluginMessage("minecraft:brand", PluginMessage.brandPayload(brand)).encode(configOut));
        MinecraftFrames.write(out, new PluginMessage("neoforge:register", payload).encode(configOut));
        MinecraftFrames.write(out, new byte[] {finishOut});
        byte[] packet;
        do {
          packet = MinecraftFrames.read(in, 8192);
          received.add(packet);
        } while (!(packet.length == 1 && packet[0] == finishIn));
        MinecraftFrames.write(out, joinGame765());
        while (true) received.add(MinecraftFrames.read(in, 8192));
      } catch (java.io.IOException closed) { /* the switch closes the old backend */ }
      catch (Throwable unexpected) { failure.set(unexpected); }
    }
  }

  /** Collects a whole Configuration phase, ending on the backend's Finish Configuration. */
  private static List<byte[]> readConfiguration(InputStream in, byte finish) throws Exception {
    List<byte[]> packets = new java.util.ArrayList<>();
    while (true) {
      byte[] packet = MinecraftFrames.read(in, 8192);
      if (packet.length == 1 && packet[0] == finish) return packets;
      packets.add(packet);
    }
  }

  private static void readUntil(InputStream in, int id) throws Exception {
    while (MinecraftFrames.read(in, 8192)[0] != (byte) id) { }
  }

  /** The payload of the one plugin message on {@code channel}, or null. */
  private static byte[] payload(List<byte[]> packets, int pluginMessageId, String channel) throws Exception {
    synchronized (packets) {
      for (byte[] packet : packets) {
        if (packet.length == 0 || packet[0] != (byte) pluginMessageId) continue;
        try {
          PluginMessage message = PluginMessage.decodeBody(Arrays.copyOfRange(packet, 1, packet.length), 8192);
          if (channel.equals(message.channel())) return message.data();
        } catch (Exception notAPluginMessage) { /* some other packet with the same id */ }
      }
    }
    return null;
  }

  private static byte[] command(int id, String text) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(out, id);
      MinecraftOutput.string(out, text);
    }
    return bytes.toByteArray();
  }

  /** 1.20.4 Join Game, id 0x29: a survival overworld with no death location. */
  private static byte[] joinGame765() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(out, 0x29);
      out.writeInt(7);
      out.writeBoolean(false);
      MinecraftOutput.varInt(out, 1);
      MinecraftOutput.string(out, "minecraft:overworld");
      MinecraftOutput.varInt(out, 20);
      MinecraftOutput.varInt(out, 10);
      MinecraftOutput.varInt(out, 10);
      out.writeBoolean(false);
      out.writeBoolean(true);
      out.writeBoolean(false);
      MinecraftOutput.string(out, "minecraft:overworld");
      MinecraftOutput.string(out, "minecraft:overworld");
      out.writeLong(0L);
      out.writeByte(0);
      out.writeByte(-1);
      out.writeBoolean(false);
      out.writeBoolean(false);
      out.writeBoolean(false);
      MinecraftOutput.varInt(out, 0);
    }
    return bytes.toByteArray();
  }

  // ---- captured from the real NeoForge 20.2.93 run, 2026-09-17 --------------------------------

  /** Handshake frame, client to Conduit. Protocol 764, host "127.0.0.1\0FML3", port 25579. */
  private static final String CAPTURED_HANDSHAKE = "1500fc050e3132372e302e302e3100464d4c3363eb02";
  /** Login Start frame: name NF1202 and the offline UUID the harness launched with. */
  private static final String CAPTURED_LOGIN_START = "1800064e463132303200000000000000000000000000025599";
  /** First 96 bytes of Login Plugin Request 0x00, backend to Conduit. The frame is 108 bytes. */
  private static final String CAPTURED_QUERY_0 =
      "6b040010666d6c3a6c6f67696e777261707065720010666d6c3a6c6f67696e777261707065720d666d6c3a68616e"
      + "647368616b65370502096d696e656372616674094d696e65637261667406312e32302e32086e656f666f726765084e656f46";
  /**
   * Frame sizes of Login Plugin Requests 0x00 through 0x14, in the order the server sent them,
   * all inside one second and none of them waiting for a reply.
   */
  private static final int[] CAPTURED_QUERY_FRAMES = {
      108, 651, 1218, 53044, 2281, 389, 134, 34719, 90, 206, 638,
      986, 28211, 290, 804, 246, 590, 297, 944, 2653, 1312};

  /**
   * The captured login, replayed against the real codecs.
   *
   * <p>This is what the re-run has to get past. It is not a claim that it does: nothing here talks
   * to a NeoForge server. It pins the shape Conduit has to survive, so a failure in the re-run can
   * be told apart from a failure in the assumptions.
   */
  private static void capturedNeoForgeLogin() throws Exception {
    Handshake handshake = Handshake.decode(frame(CAPTURED_HANDSHAKE));
    require(handshake.protocolVersion() == 764, "the captured client is protocol 764 (1.20.2)");
    require(handshake.nextState() == 2, "and it is logging in, not pinging");
    var parsed = FmlAddressMarkers.parse(handshake.requestedHost());
    require(parsed.marker() == FmlAddressMarkers.MarkerKind.FML3, "NeoForge 20.2.93 sends FML3");
    require(parsed.cleanHost().equals("127.0.0.1"), "and the address behind it is intact");
    require(ProtocolDefinition.hasCodec(764), "Conduit has a 1.20.2 table to read that login with");

    require(LoginStart.decode(java.util.Arrays.copyOfRange(frame(CAPTURED_LOGIN_START), 1, frame(CAPTURED_LOGIN_START).length),
        ProtocolDefinition.forVersion(764)).username().equals("NF1202"), "the captured Login Start decodes");

    ProtocolDefinition protocol = ProtocolDefinition.forVersion(764);
    byte[] query = frame(CAPTURED_QUERY_0);
    require(query[0] == (byte) protocol.id(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_PLUGIN_REQUEST),
        "query 0x00 is a Login Plugin Request on the 1.20.2 table");
    // The capture keeps 96 of the 108 bytes; the header is all inside it, the body is not.
    LoginPluginRequest decoded = LoginPluginRequest.decode(java.util.Arrays.copyOfRange(query, 1, query.length), 65536);
    require(decoded.messageId() == 0, "message id 0");
    require(decoded.channel().equals("fml:loginwrapper"), "on fml:loginwrapper");
    require(ChannelDetector.classify(decoded.channel()) == ChannelDetector.ChannelClass.FORGE, "which Conduit reads as Forge");

    // Nothing in the captured batch is near the default frame limit, and the largest is not close.
    int limit = 1_048_576;
    int total = 0;
    for (int size : CAPTURED_QUERY_FRAMES) {
      require(size < limit, "captured frame of " + size + " bytes fits the default max-frame-bytes");
      total += size;
    }
    require(CAPTURED_QUERY_FRAMES.length == 21, "21 requests");
    require(total > 128_000 && total < 140_000, "about 130 KB of login traffic before a single reply (" + total + ")");
    // The limit is a real gate, not a formality: the largest captured request is refused below it.
    expectIO(() -> LoginPluginRequest.decode(pluginBody(3, "fml:loginwrapper", new byte[53_000]), 32_768),
        "a 53 KB login query accepted under a 32 KB frame limit");
    require(LoginPluginRequest.decode(pluginBody(3, "fml:loginwrapper", new byte[53_000]), limit).data().length == 53_000,
        "and accepted under the default one");

    capturedLoginReplayed(protocol);
  }

  /**
   * The predicted remainder of that login, in the captured order: the batch, then Set Compression,
   * then Login Success. Set Compression has not been seen on this wire — the server sent none
   * before the batch — so it is here because a modded server enables it before the registry data
   * that follows, and it is the packet that must never reach a client whose link is uncompressed.
   */
  private static void capturedLoginReplayed(ProtocolDefinition protocol) throws Exception {
    BackendLoginPipeline pipeline = pipeline(protocol);
    pipeline.allowClientLoginQueries();
    for (int id = 0; id < CAPTURED_QUERY_FRAMES.length; id++) {
      require(pipeline.onBackendPacket(loginPluginRequest(protocol, id, "fml:loginwrapper", new byte[8]), 1_048_576) == null,
          "query " + id + " is the client's to answer");
      require(pipeline.shouldForward(), "query " + id + " reaches the client");
    }
    require(pipeline.outstandingLoginQueries() == 21, "the whole batch is outstanding before any answer");
    for (int id = 0; id < CAPTURED_QUERY_FRAMES.length; id++) {
      pipeline.clientLoginQueryResponse(loginPluginResponse(protocol, id, new byte[] {1}));
    }
    require(!pipeline.awaitingLoginQuery(), "and every one is settled");

    ByteArrayOutputStream setCompression = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(setCompression)) {
      MinecraftOutput.varInt(out, protocol.id(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_SET_COMPRESSION));
      MinecraftOutput.varInt(out, 256);
    }
    require(pipeline.onBackendPacket(setCompression.toByteArray(), 1_048_576) == null, "Set Compression needs no answer");
    require(!pipeline.shouldForward(), "and must not reach a client whose link Conduit never compresses");
    require(pipeline.compression().enabled(), "the backend link is compressed from here");

    byte[] success = new byte[] {(byte) protocol.id(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_SUCCESS)};
    require(pipeline.onBackendPacket(success, 1_048_576) == null && pipeline.shouldForward(), "Login Success reaches the client");
    require(pipeline.state() == ConnectionState.CONFIGURATION,
        "1.20.2 has a Configuration phase, so that is where the registry data will arrive");

    // Two threads settle these queries in the shape the fix needs: one reads the backend and adds,
    // one reads the client and removes. Same monitor, or the set tears.
    BackendLoginPipeline shared = pipeline(protocol);
    shared.allowClientLoginQueries();
    for (int id = 0; id < 200; id++) shared.onBackendPacket(loginPluginRequest(protocol, id, "fml:loginwrapper", new byte[0]), 65536);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread answers = Thread.startVirtualThread(() -> {
      try {
        for (int id = 0; id < 200; id++) shared.clientLoginQueryResponse(loginPluginResponse(protocol, id, new byte[0]));
      } catch (Throwable thrown) { failure.set(thrown); }
    });
    for (int id = 200; id < 400; id++) {
      try { shared.onBackendPacket(loginPluginRequest(protocol, id, "fml:loginwrapper", new byte[0]), 65536); }
      catch (java.io.IOException atTheCap) { break; }
    }
    answers.join(10_000);
    if (failure.get() != null) throw new AssertionError("concurrent settle failed", failure.get());
    require(!answers.isAlive(), "the answering thread finished");
  }

  private static byte[] frame(String hex) throws Exception {
    byte[] bytes = new byte[hex.length() / 2];
    for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
    // Every capture line is a whole TCP write starting with the frame's length VarInt; drop it.
    try (java.io.DataInputStream input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes))) {
      gg.tame.conduit.protocol.MinecraftInput.varInt(input);
      return input.readAllBytes();
    }
  }

  private static byte[] pluginBody(int messageId, String channel, byte[] data) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(out, messageId);
      MinecraftOutput.string(out, channel);
      out.write(data);
    }
    return bytes.toByteArray();
  }

  private static BackendLoginPipeline pipeline(ProtocolDefinition protocol) throws Exception {
    return new BackendLoginPipeline(protocol, new NoneForwarder(),
        new PlayerProfile(new UUID(1, 2), "playr", List.of(), false), InetAddress.getByName("127.0.0.1"), 1_048_576);
  }

  private static BackendLoginPipeline pipeline() throws Exception {
    return new BackendLoginPipeline(ProtocolDefinition.forVersion(PROTOCOL), new NoneForwarder(),
        new PlayerProfile(new UUID(1, 2), "playr", List.of(), false), InetAddress.getByName("127.0.0.1"), 4096);
  }

  private static byte[] loginPluginRequest(ProtocolDefinition protocol, int messageId, String channel, byte[] data) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(out, protocol.id(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_PLUGIN_REQUEST));
      MinecraftOutput.varInt(out, messageId);
      MinecraftOutput.string(out, channel);
      out.write(data);
    }
    return bytes.toByteArray();
  }

  private static byte[] loginPluginResponse(ProtocolDefinition protocol, int messageId, byte[] data) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(out, protocol.id(ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, PacketKind.LOGIN_PLUGIN_RESPONSE));
      MinecraftOutput.varInt(out, messageId);
      out.writeBoolean(true);
      out.write(data);
    }
    return bytes.toByteArray();
  }

  /** 1.20.4 Login Start: name and UUID. */
  private static byte[] loginStart() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(out, 0);
      MinecraftOutput.string(out, "playr");
      out.writeLong(0L);
      out.writeLong(0L);
    }
    return bytes.toByteArray();
  }

  /** 1.20.4 Login Success: UUID, name, no properties. */
  private static byte[] loginSuccess() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(out, 0x02);
      out.writeLong(0L);
      out.writeLong(0L);
      MinecraftOutput.string(out, "playr");
      MinecraftOutput.varInt(out, 0);
    }
    return bytes.toByteArray();
  }

  private static String printable(String host) {
    return host == null ? "nothing" : host.replace("\0", "\\0");
  }

  private interface Body { void run() throws Exception; }

  private static void expectIO(Body body, String message) {
    try {
      body.run();
      throw new AssertionError(message);
    } catch (java.io.IOException expected) { /* ok */ }
    catch (AssertionError failure) { throw failure; }
    catch (Exception unexpected) { throw new AssertionError(message, unexpected); }
  }

  /**
   * A heavily modded 1.20.5+ client declares a known pack per mod. Velocity capped the list at 64
   * and dropped such a client; Conduit's limit is {@code [modded] known-packs-limit} (1024). A list
   * inside it reaches the backend unchanged, and one past it is refused with a reason the player
   * can read -- before, the refusal was swallowed and the client got a dropped socket, and the
   * Configuration-phase kick carried a Play chat packet no Configuration client can parse.
   */
  private static void knownPacksOfAModdedClient() throws Exception {
    byte[] modpack = knownPacks(1000);
    require(knownPacksThroughProxy(modpack, true) == null, "1000 known packs, past Velocity's 64, reach the backend");
    byte[] refusal = knownPacksThroughProxy(knownPacks(1025), false);
    require(refusal[0] == 0x02, "the client gets 1.21's Configuration Disconnect, not a Play chat packet (got id " + refusal[0] + ")");
    require(new String(refusal, java.nio.charset.StandardCharsets.UTF_8).contains("resource packs (1025)"),
        "the refusal says how many packs the client declared");
  }

  /** Logs a scripted 1.21 client in and sends its Known Packs; returns what the client got back instead, or null. */
  private static byte[] knownPacksThroughProxy(byte[] clientKnownPacks, boolean reachesBackend) throws Exception {
    int v121 = 767;
    UUID id = new UUID(7, 7);
    try (ServerSocket backendListener = new ServerSocket(0)) {
      AtomicReference<Throwable> backendFailure = new AtomicReference<>();
      AtomicReference<byte[]> backendGot = new AtomicReference<>();
      Thread backend = Thread.startVirtualThread(() -> {
        try (Socket socket = backendListener.accept()) {
          socket.setSoTimeout(5_000);
          InputStream in = socket.getInputStream();
          OutputStream out = socket.getOutputStream();
          MinecraftFrames.read(in, 1 << 20);
          MinecraftFrames.read(in, 1 << 20);
          ByteArrayOutputStream success = new ByteArrayOutputStream();
          try (DataOutputStream body = new DataOutputStream(success)) {
            body.writeByte(0x02);
            body.writeLong(id.getMostSignificantBits());
            body.writeLong(id.getLeastSignificantBits());
            MinecraftOutput.string(body, "player");
            MinecraftOutput.varInt(body, 0);
            body.writeBoolean(true);
          }
          MinecraftFrames.write(out, success.toByteArray());
          require(MinecraftFrames.read(in, 1 << 20)[0] == 0x03, "backend gets Login Acknowledged");
          MinecraftFrames.write(out, new byte[] {0x0E, 1, 9, 'm', 'i', 'n', 'e', 'c', 'r', 'a', 'f', 't', 4, 'c', 'o', 'r', 'e', 4, '1', '.', '2', '1'});
          try {
            for (byte[] packet = MinecraftFrames.read(in, 1 << 20); ; packet = MinecraftFrames.read(in, 1 << 20)) {
              if (packet[0] == 0x07) { backendGot.set(packet); break; }
            }
          } catch (java.io.IOException closed) { }
        } catch (Throwable failure) {
          backendFailure.set(failure);
        }
      });
      ConduitConfiguration configuration = new ConduitConfiguration(new InetSocketAddress("127.0.0.1", reservePort()), 1 << 20,
          ForwardingMode.NONE, Optional.empty(),
          List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", backendListener.getLocalPort()))),
          List.of("lobby"), List.of());
      byte[] reply = null;
      try (MinecraftProxy proxy = new MinecraftProxy(configuration)) {
        Thread serving = Thread.startVirtualThread(() -> { try { proxy.serve(); } catch (Exception ignored) { } });
        try (Socket client = new Socket("127.0.0.1", proxy.port())) {
          client.setSoTimeout(5_000);
          InputStream in = client.getInputStream();
          OutputStream out = client.getOutputStream();
          MinecraftFrames.write(out, new Handshake(v121, "local", 25565, 2).encode());
          MinecraftFrames.write(out, LoginStart.encode(new PlayerProfile(id, "player", List.of(), false), ProtocolDefinition.forVersion(v121)));
          require(MinecraftFrames.read(in, 1 << 20)[0] == 0x02, "the 1.21 client gets Login Success");
          MinecraftFrames.write(out, new byte[] {0x03});
          readUntil(in, 0x0E);
          MinecraftFrames.write(out, clientKnownPacks);
          if (!reachesBackend) {
            try { reply = MinecraftFrames.read(in, 1 << 20); }
            catch (java.io.EOFException dropped) { throw new AssertionError("a refused client got a dropped socket and no reason"); }
          }
          backend.join(5_000);
        }
        serving.interrupt();
      }
      if (backendFailure.get() != null) throw new AssertionError("mock backend: " + backendFailure.get(), backendFailure.get());
      if (reachesBackend) require(Arrays.equals(backendGot.get(), clientKnownPacks), "the backend gets the client's Known Packs byte for byte");
      else require(backendGot.get() == null, "a refused Known Packs list never reaches the backend");
      return reply;
    }
  }

  private static byte[] knownPacks(int count) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      out.writeByte(0x07);
      MinecraftOutput.varInt(out, count);
      for (int i = 0; i < count; i++) {
        MinecraftOutput.string(out, "mod" + i);
        MinecraftOutput.string(out, "resources");
        MinecraftOutput.string(out, "1.0." + i);
      }
    }
    return bytes.toByteArray();
  }

  private static int reservePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
