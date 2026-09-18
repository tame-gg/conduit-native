// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.config.ConfigurationLoader;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.ProtocolSession;
import gg.tame.conduit.protocol.VarIntFrameDecoder;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.routing.BackendSelector;
import gg.tame.conduit.auth.HasJoinedResponse;
import gg.tame.conduit.auth.MojangSessionAuthenticator;
import gg.tame.conduit.auth.SessionQuery;
import gg.tame.conduit.network.MinecraftProxy;
import gg.tame.conduit.network.PacketTransport;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.config.ForwardingMode;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.protocol.StatusResponder;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.forwarding.ForwardingSecret;
import gg.tame.conduit.forwarding.ModernForwarder;
import gg.tame.conduit.forwarding.ModernForwardingVersion;
import gg.tame.conduit.forwarding.ForwardingRequest;
import gg.tame.conduit.login.PlayerProfile;
import gg.tame.conduit.login.ProfileProperty;
import gg.tame.conduit.login.BackendLoginPipeline;
import gg.tame.conduit.login.LoginPipeline;
import gg.tame.conduit.login.LoginPluginRequest;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.MinecraftInput;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.DataInputStream;
import java.io.ByteArrayInputStream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.util.UUID;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AllTests {
  public static void main(String[] arguments) throws Exception {
    decodeFramesWithoutOverAllocation();
    enforceProtocolTransitions();
    validateIndependentConfiguration();
    decodeHandshakeAndSelectBackend();
    proxyRelaysHandshakeAndBackendData();
    createAuthenticatedModernForwardingPayload();
    answerStatusAndPing();
    completeModernBackendExchange();
    keepSetCompressionOffTheClient();
    rejectInvalidLoginPluginRequests();
    mockBackendModernForwardingWireExchange();
    encryptionAndServerHash();
    encryptionHelloLayout();
    sessionAuthentication();
    onlineModeFeedsAuthenticatedIdentityToForwarding();
    ProfileTests.run();
    Phase6Tests.run();
    Phase8Tests.run();
    Phase9Tests.run();
    Phase10Tests.run();
    Phase11OpsTests.run();
    Phase12SecurityTests.run();
    Phase13ModdedTests.run();
    Phase14ProtocolTranslationTests.run();
    Phase16ModernProtocolTests.run();
    Phase17_393_765_TranslationTests.run();
    Phase18_393_765_WorldTests.run();
    Phase19_393_765_ItemTests.run();
    Phase20_393_404_TranslationTests.run();
    Phase21_404_477_TranslationTests.run();
    Phase22_404_477_SemanticTests.run();
    RecipeListRepairTests.run();
    ViaIntegrationTests.run();
    ViaSwitchBridgeTests.run();
    CompatibilityProbeTests.run();
    SwitchJoinGateTests.run();
    ViaOrderingTests.run();
    DirectLoginCompressionTests.run();
    ConcurrencyTests.run();
    CommandApiTests.run();
    NativeApiTests.run();
    PluginRuntimeTests.run();
    MalformedInputTests.run();
    ModLoaderTests.run();
    VelocityCompatTests.run();
    System.out.println("All Conduit foundation tests passed.");
    // Release Via's non-daemon platform executors so this JVM can exit on its own.
    gg.tame.conduit.viaversion.ConduitViaBootstrap.stop();
  }
  private static void decodeFramesWithoutOverAllocation() {
    VarIntFrameDecoder decoder = new VarIntFrameDecoder(16);
    ByteBuffer incomplete = ByteBuffer.wrap(new byte[] {3, 1});
    require(decoder.tryDecode(incomplete).isEmpty(), "partial frame must wait");
    ByteBuffer complete = ByteBuffer.wrap(new byte[] {3, 1, 2, 3});
    require(java.util.Arrays.equals(decoder.tryDecode(complete).orElseThrow(), new byte[] {1, 2, 3}), "payload must round trip");
    try { decoder.tryDecode(ByteBuffer.wrap(new byte[] {17})); throw new AssertionError("oversized frame accepted"); }
    catch (IllegalArgumentException expected) { }
  }
  private static void enforceProtocolTransitions() throws Exception {
    ProtocolSession session = new ProtocolSession();
    session.acceptHandshake(1); require(session.state() == ConnectionState.STATUS, "status transition failed");
    try { session.acceptHandshake(2); throw new AssertionError("duplicate handshake accepted"); }
    catch (IllegalStateException expected) { }
    ProtocolSession login = new ProtocolSession();
    login.acceptHandshake(2);
    require(login.state() == ConnectionState.LOGIN, "handshaking to login failed");
    ProtocolDefinition protocol = ProtocolDefinition.forVersion(765);
    LoginPipeline pipeline = new LoginPipeline(login, protocol);
    pipeline.observe(PacketDirection.CLIENT_TO_SERVER, loginStart());
    pipeline.observe(PacketDirection.SERVER_TO_CLIENT, new byte[] {2});
    require(login.state() == ConnectionState.CONFIGURATION, "login to configuration failed");
    login.beginPlay();
    require(login.state() == ConnectionState.PLAY, "client finish-configuration ack enters play");
    require(protocol.hasConfiguration(), "1.20.4 uses configuration");
    require(protocol.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DECLARE_COMMANDS) == 0x11, "declare commands id");
    ProtocolDefinition legacy = ProtocolDefinition.forVersion(763);
    require(!legacy.hasConfiguration() && legacy.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN) == 0x28, "1.20.1 join game");
    require(!legacy.defines(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_START_CONFIGURATION), "1.20.1 has no start configuration");
    try { ProtocolDefinition.forVersion(999); throw new AssertionError("unknown protocol accepted"); }
    catch (IllegalArgumentException expected) { }
    require(protocol.id(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_PLUGIN_REQUEST) == 4, "1.20.4 login plugin request id");
    require(protocol.id(ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, PacketKind.LOGIN_PLUGIN_RESPONSE) == 2, "1.20.4 login plugin response id");
    require(protocol.id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_FINISH) == 2, "1.20.4 finish configuration id");
    ProtocolDefinition current = ProtocolDefinition.forVersion(776);
    require(current.id(ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, PacketKind.LOGIN_ACKNOWLEDGED) == 3, "26.2 login ack");
    require(current.id(ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_FINISH) == 3, "26.2 config finish shares login-ack id");
    require(current.knownPacks() && current.id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_KNOWN_PACKS) == 0x0E, "26.2 known packs");
    require(current.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_START_CONFIGURATION) == 0x76, "26.2 start configuration");
    require(gg.tame.conduit.protocol.ProtocolCompatibility.between(776, 776) == gg.tame.conduit.protocol.TranslationSupport.DIRECT, "26.2 direct");
    require(gg.tame.conduit.protocol.ProtocolCompatibility.between(765, 776) == gg.tame.conduit.protocol.TranslationSupport.UNSUPPORTED, "no native 1.20.4 to 26.2 translation");
    require(gg.tame.conduit.protocol.ProtocolDefinition.hasCodec(766), "1.20.5 codec");
    require(gg.tame.conduit.protocol.ProtocolCompatibility.between(765, 766) == gg.tame.conduit.protocol.TranslationSupport.TRANSLATED, "765↔766 translated");
    var parsed = gg.tame.conduit.protocol.BackendStatusProbe.parse("{\"version\":{\"name\":\"Paper 26.2\",\"protocol\":776}}");
    require(parsed.orElseThrow().protocol() == 776, "status protocol parse");
    require(protocol.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_START_CONFIGURATION) == 0x67, "start configuration id");
    ProtocolSession reconfigure = new ProtocolSession();
    reconfigure.acceptHandshake(2); reconfigure.beginConfiguration(); reconfigure.beginPlay(); reconfigure.beginReconfiguration();
    require(reconfigure.state() == ConnectionState.CONFIGURATION, "reconfiguration");
  }
  private static void validateIndependentConfiguration() throws Exception {
    Path config = TempFiles.file("conduit", ".toml");
    Files.writeString(config, "[listener]\nhost=\"127.0.0.1\"\nport=25565\nmax-frame-bytes=64\n[forwarding]\nmode=\"none\"\n[servers.lobby]\nhost=\"127.0.0.1\"\nport=25566\n[routing]\ninitial=[\"lobby\"]\nfallback=[\"lobby\"]\n");
    require(ConfigurationLoader.load(config).maxFrameBytes() == 64, "configuration did not load");
    Files.writeString(config, "[listener]\nhost=\"127.0.0.1\"\nport=25565\nmax-frame-bytes=64\n[forwarding]\nmode=\"none\"\n[servers.smp]\naddress=\"127.0.0.1:25921\"\n[routing]\ninitial=[\"smp\"]\nfallback=[\"smp\"]\n");
    require(ConfigurationLoader.load(config).backends().getFirst().address().getPort() == 25921, "address form");
    require(ConfigurationLoader.load(config).authentication().mode() == gg.tame.conduit.config.AuthenticationMode.OFFLINE, "missing authentication must default to offline");
    Files.writeString(config, configuration("modern"));
    try { ConfigurationLoader.load(config); throw new AssertionError("modern mode accepted without secret"); }
    catch (IllegalArgumentException expected) { }
  }
  private static void decodeHandshakeAndSelectBackend() throws Exception {
    byte[] packet = new byte[] {0, (byte) 0xFD, 5, 5, 'l', 'o', 'c', 'a', 'l', 0x63, (byte) 0xDD, 2};
    Handshake handshake = Handshake.decode(packet);
    require(handshake.protocolVersion() == 765 && handshake.requestedHost().equals("local") && handshake.requestedPort() == 25565 && handshake.nextState() == 2, "handshake failed");
    Path config = TempFiles.file("conduit", ".toml"); Files.writeString(config, configuration("none"));
    require(new BackendSelector(ConfigurationLoader.load(config)).candidates().getFirst().name().equals("lobby"), "backend selection failed");
    Files.writeString(config, "[listener]\nhost=\"127.0.0.1\"\nport=25565\nmax-frame-bytes=64\n[forwarding]\nmode=\"none\"\n[servers.lobby]\nhost=\"127.0.0.1\"\nport=1\n[servers.smp]\nhost=\"127.0.0.1\"\nport=2\n[routing]\ninitial=[\"lobby\"]\nfallback=[\"lobby\"]\n");
    require(new BackendSelector(ConfigurationLoader.load(config)).candidatesFor(776).getFirst().name().equals("lobby"), "26.2 must not skip routing.initial");
    Files.writeString(config, "[listener]\nhost=\"127.0.0.1\"\nport=25565\nmax-frame-bytes=64\n[forwarding]\nmode=\"none\"\nplayer-address=\"203.0.113.9\"\n[servers.lobby]\nhost=\"127.0.0.1\"\nport=1\n[routing]\ninitial=[\"lobby\"]\nfallback=[\"lobby\"]\n");
    require(ConfigurationLoader.load(config).forwardedPlayerAddress().orElseThrow().getHostAddress().equals("203.0.113.9"), "forwarding player-address");
  }
  private static void proxyRelaysHandshakeAndBackendData() throws Exception {
    try (ServerSocket backendListener = new ServerSocket(0)) {
      Thread backend = Thread.startVirtualThread(() -> {
        try (Socket socket = backendListener.accept()) {
          byte[] handshake = MinecraftFrames.read(socket.getInputStream(), 128);
          require(Handshake.decode(handshake).nextState() == 2, "proxy did not forward handshake");
          MinecraftFrames.read(socket.getInputStream(), 128);
          // Conduit completes the backend login itself, in every forwarding mode, before relaying.
          MinecraftFrames.write(socket.getOutputStream(), loginSuccess());
          MinecraftFrames.write(socket.getOutputStream(), new byte[] {1, 42});
          // Reads Conduit's Login Acknowledged. Closing with it unread resets the connection, and the
          // reset discards the packet above before Conduit has read it.
          drain(socket);
        } catch (Exception exception) { throw new RuntimeException(exception); }
      });
      int proxyPort;
      ConduitConfiguration configuration = new ConduitConfiguration(new InetSocketAddress("127.0.0.1", reservePort()), 128,
          ForwardingMode.NONE, Optional.empty(), List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", backendListener.getLocalPort()))), List.of("lobby"), List.of());
      try (MinecraftProxy proxy = new MinecraftProxy(configuration)) {
        proxyPort = proxy.port();
        Thread serving = Thread.startVirtualThread(() -> { try { proxy.serve(); } catch (Exception ignored) { } });
        try (Socket client = new Socket("127.0.0.1", proxyPort)) {
          MinecraftFrames.write(client.getOutputStream(), new byte[] {0, (byte) 0xFD, 5, 5, 'l', 'o', 'c', 'a', 'l', 0x63, (byte) 0xDD, 2});
          MinecraftFrames.write(client.getOutputStream(), loginStart());
          require(java.util.Arrays.equals(MinecraftFrames.read(client.getInputStream(), 128), loginSuccess()), "proxy did not relay Login Success");
          require(java.util.Arrays.equals(MinecraftFrames.read(client.getInputStream(), 128), new byte[] {1, 42}), "proxy did not relay backend packet");
        }
        backend.join(); serving.interrupt();
      }
    }
  }
  private static void createAuthenticatedModernForwardingPayload() throws Exception {
    Path secret = TempFiles.file("conduit-forwarding", ".secret"); Files.writeString(secret, "do-not-log-me");
    ForwardingSecret loaded = ForwardingSecret.load(secret);
    require(loaded.toString().equals("ForwardingSecret[redacted]"), "secret toString must stay redacted");
    require(!loaded.fingerprint().contains("do-not-log-me"), "fingerprint leaked secret");
    ModernForwarder forwarder = new ModernForwarder(loaded);
    UUID uuid = new UUID(1, 2);
    PlayerProfile player = new PlayerProfile(uuid, "player", List.of(new ProfileProperty("textures", "value", Optional.of("sig"))), true);
    ForwardingRequest request = new ForwardingRequest(player, InetAddress.getByName("127.0.0.1"), 765, 1);
    byte[] first = forwarder.payload(request); byte[] second = forwarder.payload(request);
    require(first.length > 32 && java.util.Arrays.equals(first, second), "modern forwarding must be signed deterministically");
    require(!new String(first, StandardCharsets.ISO_8859_1).contains("do-not-log-me"), "secret leaked into payload");
    byte[] signed = java.util.Arrays.copyOfRange(first, 32, first.length);
    require(java.util.Arrays.equals(java.util.Arrays.copyOf(first, 32), hmac("do-not-log-me", signed)), "HMAC-SHA-256 mismatch");
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(signed))) {
      require(MinecraftInput.varInt(input) == 1, "forwarding version");
      require(MinecraftInput.string(input, 255).equals("127.0.0.1"), "forwarded IP");
      require(new UUID(input.readLong(), input.readLong()).equals(uuid), "forwarded UUID");
      require(MinecraftInput.string(input, 16).equals("player"), "forwarded username");
      require(MinecraftInput.varInt(input) == 1, "property count");
      require(MinecraftInput.string(input, 255).equals("textures"), "property name");
      require(MinecraftInput.string(input, 255).equals("value"), "property value");
      require(input.readBoolean() && MinecraftInput.string(input, 255).equals("sig"), "property signature");
      require(input.available() == 0, "unexpected trailing forwarding bytes");
    }
    try { forwarder.payload(new ForwardingRequest(player, InetAddress.getByName("127.0.0.1"), 765, 5)); throw new AssertionError("unsupported forwarding version accepted"); }
    catch (IllegalArgumentException expected) { }
    byte[] lazy = forwarder.payload(new ForwardingRequest(player, InetAddress.getByName("127.0.0.1"), 765, 4));
    require(MinecraftInput.varInt(new DataInputStream(new ByteArrayInputStream(java.util.Arrays.copyOfRange(lazy, 32, lazy.length)))) == 4, "lazy-session version was not preserved");
    Path empty = TempFiles.file("conduit-empty", ".secret"); Files.writeString(empty, " \n");
    try { ForwardingSecret.load(empty); throw new AssertionError("empty secret accepted"); }
    catch (IllegalArgumentException expected) { }
  }
  private static void answerStatusAndPing() throws Exception {
    ProtocolDefinition protocol = ProtocolDefinition.forVersion(765);
    byte[] response = StatusResponder.response(protocol, new byte[] {0}, "Conduit");
    require(new String(response, StandardCharsets.UTF_8).contains("Conduit"), "status response missing description");
    byte[] pong = StatusResponder.pong(protocol, new byte[] {1, 0, 0, 0, 0, 0, 0, 0, 7});
    require(pong[pong.length - 1] == 7, "status ping nonce was not echoed");
  }
  private static void completeModernBackendExchange() throws Exception {
    Path secret = TempFiles.file("conduit-forwarding", ".secret"); Files.writeString(secret, "exchange-secret");
    BackendLoginPipeline pipeline = pipeline(secret);
    byte[] response = pipeline.onBackendPacket(modernRequest(17, 1), 1024);
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(response))) {
      require(MinecraftInput.varInt(input) == 2 && MinecraftInput.varInt(input) == 17 && input.readBoolean(), "incorrect login plugin response header");
      byte[] signature = input.readNBytes(32); byte[] signed = input.readAllBytes();
      require(java.util.Arrays.equals(signature, hmac("exchange-secret", signed)), "modern forwarding HMAC invalid");
      require(MinecraftInput.varInt(new DataInputStream(new ByteArrayInputStream(signed))) == 1, "forwarding version was not preserved");
    }
    require(pipeline.onBackendPacket(new byte[] {2}, 1024) == null && pipeline.state() == ConnectionState.CONFIGURATION, "Login Success transition failed");
    require(pipeline.onBackendPacket(new byte[] {2}, 1024) == null && pipeline.state() == ConnectionState.PLAY, "Finish Configuration transition failed");
    try { pipeline(secret).onBackendPacket(modernRequest(1, 5), 1024); throw new AssertionError("unsupported forwarding version accepted"); }
    catch (java.io.IOException expected) { }
    // Versions 2 and 3 carry the player's chat signing key, which Conduit does not forward. A request
    // names the highest version the backend reads, so they are answered in version 1: a real Paper
    // 1.19 asked for 2 and accepted 1, and answered "2" in the key-less layout it failed reading the
    // key's expiry (IndexOutOfBoundsException), as a real 1.19.2 did for 3.
    for (int keyed : new int[] {ModernForwardingVersion.V2_WITH_KEY, ModernForwardingVersion.V3_WITH_KEY_V2}) {
      byte[] keyedResponse = pipeline(secret).onBackendPacket(modernRequest(7, keyed), 1024);
      try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(keyedResponse))) {
        require(MinecraftInput.varInt(input) == 2 && MinecraftInput.varInt(input) == 7 && input.readBoolean(), "keyed request answered");
        input.readNBytes(32);
        require(MinecraftInput.varInt(input) == ModernForwardingVersion.V1_DEFAULT, "a version " + keyed + " request is answered in version 1");
      }
    }
    byte[] lazyResponse = pipeline(secret).onBackendPacket(modernRequest(-333808985, 4), 1024);
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(lazyResponse))) {
      require(MinecraftInput.varInt(input) == 2 && MinecraftInput.varInt(input) == -333808985 && input.readBoolean(), "negative Paper message id must be echoed");
      input.readNBytes(32);
      require(MinecraftInput.varInt(input) == 4, "Paper lazy-session version must be echoed");
    }
  }
  private static void keepSetCompressionOffTheClient() throws Exception {
    Path secret = TempFiles.file("conduit-forwarding", ".secret"); Files.writeString(secret, "exchange-secret");
    BackendLoginPipeline pipeline = pipeline(secret);
    ByteArrayOutputStream setCompression = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(setCompression)) {
      MinecraftOutput.varInt(output, 3);
      MinecraftOutput.varInt(output, 256);
    }
    require(pipeline.onBackendPacket(setCompression.toByteArray(), 1024) == null, "set compression has no plugin response");
    require(!pipeline.shouldForward(), "set compression must not reach the client");
    require(pipeline.compression().enabled(), "backend compression enabled");
    byte[] tiny = {9, 8, 7};
    byte[] wrapped = pipeline.compression().wrap(tiny);
    require(wrapped[0] == 0 && wrapped[1] == 9, "below-threshold packets stay uncompressed with a 0 prefix");
    require(java.util.Arrays.equals(pipeline.compression().unwrap(wrapped), tiny), "unwrap round trip");
  }
  private static void rejectInvalidLoginPluginRequests() throws Exception {
    Path secret = TempFiles.file("conduit-forwarding", ".secret"); Files.writeString(secret, "exchange-secret");
    byte[] valid = pipeline(secret).onBackendPacket(pluginRequest(3, "velocity:player_info", new byte[] {1}), 1024);
    require(valid != null && valid[1] == 3, "valid request id must be echoed");
    LoginPluginRequest negative = LoginPluginRequest.decode(pluginBody(-1, "velocity:player_info", new byte[] {1}), 1024);
    require(negative.messageId() == -1, "signed login plugin message ids must round-trip");
    expectIO(() -> pipeline(secret).onBackendPacket(pluginRequest(1, "minecraft:brand", new byte[] {1}), 1024), "unknown channel accepted");
    expectIO(() -> pipeline(secret).onBackendPacket(pluginRequest(1, "velocity:player_info", new byte[] {1, 2}), 1024), "malformed payload accepted");
    // A backend that predates the version byte asks with no data at all and reads only the first
    // format. Paper 1.13.1 does (message id 2030080267, captured from a real server); refusing it
    // sent every modern-forwarding join to a Paper backend of that age to the fallback server.
    byte[] versionless = pipeline(secret).onBackendPacket(pluginRequest(2030080267, "velocity:player_info", new byte[0]), 1024);
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(versionless))) {
      require(MinecraftInput.varInt(input) == 2 && MinecraftInput.varInt(input) == 2030080267 && input.readBoolean(), "versionless request answered with its message id");
      input.readNBytes(32);
      require(MinecraftInput.varInt(input) == ModernForwardingVersion.V1_DEFAULT, "versionless request answered in forwarding version 1");
    }
    expectIO(() -> LoginPluginRequest.decode(pluginBody(1, "velocity:player_info", new byte[8]), 4), "oversized payload accepted");
    expectIO(() -> LoginPluginRequest.decode(new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80}, 1024), "malformed VarInt accepted");
    expectIO(() -> LoginPluginRequest.decode(new byte[] {(byte) 0x80}, 1024), "truncated packet accepted");
    expectIO(() -> pipeline(secret).onBackendPacket(new byte[] {1}, 1024), "encryption request accepted");
  }
  private static void mockBackendModernForwardingWireExchange() throws Exception {
    Path secret = TempFiles.file("conduit-forwarding", ".secret"); Files.writeString(secret, "wire-secret");
    try (ServerSocket backendListener = new ServerSocket(0)) {
      Thread backend = Thread.startVirtualThread(() -> {
        try (Socket socket = backendListener.accept()) {
          MinecraftFrames.read(socket.getInputStream(), 2048);
          byte[] login = MinecraftFrames.read(socket.getInputStream(), 2048);
          require(login[0] == 0, "mock backend did not receive Login Start");
          MinecraftFrames.write(socket.getOutputStream(), modernRequest(9, 1));
          byte[] response = MinecraftFrames.read(socket.getInputStream(), 2048);
          try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(response))) {
            require(MinecraftInput.varInt(input) == 2, "response packet id");
            require(MinecraftInput.varInt(input) == 9, "response message id");
            require(input.readBoolean(), "response success flag");
            byte[] signature = input.readNBytes(32); byte[] signed = input.readAllBytes();
            require(java.util.Arrays.equals(signature, hmac("wire-secret", signed)), "mock backend rejected HMAC");
            try (DataInputStream payload = new DataInputStream(new ByteArrayInputStream(signed))) {
              require(MinecraftInput.varInt(payload) == 1, "version");
              MinecraftInput.string(payload, 255);
              require(new UUID(payload.readLong(), payload.readLong()).equals(new UUID(0, 0)), "uuid");
              require(MinecraftInput.string(payload, 16).equals("playr"), "username");
            }
          }
          MinecraftFrames.write(socket.getOutputStream(), new byte[] {2});
          MinecraftFrames.write(socket.getOutputStream(), new byte[] {2});
          drain(socket);
        } catch (Exception exception) { throw new RuntimeException(exception); }
      });
      ConduitConfiguration configuration = new ConduitConfiguration(new InetSocketAddress("127.0.0.1", reservePort()), 2048,
          ForwardingMode.MODERN, Optional.of(secret), List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", backendListener.getLocalPort()))), List.of("lobby"), List.of());
      try (MinecraftProxy proxy = new MinecraftProxy(configuration)) {
        int proxyPort = proxy.port();
        Thread serving = Thread.startVirtualThread(() -> { try { proxy.serve(); } catch (Exception ignored) { } });
        try (Socket client = new Socket("127.0.0.1", proxyPort)) {
          MinecraftFrames.write(client.getOutputStream(), new byte[] {0, (byte) 0xFD, 5, 5, 'l', 'o', 'c', 'a', 'l', 0x63, (byte) 0xDD, 2});
          MinecraftFrames.write(client.getOutputStream(), loginStart());
          require(java.util.Arrays.equals(MinecraftFrames.read(client.getInputStream(), 2048), new byte[] {2}), "client did not receive Login Success");
          byte[] next = MinecraftFrames.read(client.getInputStream(), 2048);
          if (next.length > 0 && next[0] == 0) next = MinecraftFrames.read(client.getInputStream(), 2048);
          require(java.util.Arrays.equals(next, new byte[] {2}), "client did not receive Finish Configuration");
        }
        backend.join(); serving.interrupt();
      }
    }
  }
  private static void encryptionAndServerHash() throws Exception {
    require(gg.tame.conduit.crypto.ServerHash.of("Notch".getBytes(StandardCharsets.ISO_8859_1)).equals("4ed1f46bbe04bc756bcb17c0c7ce3e4632f06a48"), "Notch hash");
    require(gg.tame.conduit.crypto.ServerHash.of("jeb_".getBytes(StandardCharsets.ISO_8859_1)).equals("-7c9d5b0044c130109a5d7b5fb5c317c02b4e28c1"), "negative jeb_ hash");
    require(gg.tame.conduit.crypto.ServerHash.of("simon".getBytes(StandardCharsets.ISO_8859_1)).equals("88e16a1019277b15d58faf0541e11910eb756f6"), "leading-zero simon hash");
    java.security.KeyPair keys = gg.tame.conduit.crypto.RsaKeys.generate();
    byte[] secret = new byte[16]; new java.security.SecureRandom().nextBytes(secret);
    byte[] encrypted = gg.tame.conduit.crypto.RsaKeys.encrypt(keys.getPublic(), secret);
    require(java.util.Arrays.equals(secret, gg.tame.conduit.crypto.RsaKeys.decrypt(keys.getPrivate(), encrypted)), "RSA round trip");
    byte[] verify = {1, 2, 3, 4};
    require(!java.util.Arrays.equals(verify, secret), "token and secret must differ");
    javax.crypto.Cipher encrypt = gg.tame.conduit.crypto.AesCfb8.encryptor(secret);
    javax.crypto.Cipher decrypt = gg.tame.conduit.crypto.AesCfb8.decryptor(secret);
    byte[] message = {9, 8, 7, 6, 5};
    require(java.util.Arrays.equals(message, decrypt.update(encrypt.update(message))), "AES/CFB8 round trip");
    java.io.ByteArrayOutputStream encryptedBytes = new java.io.ByteArrayOutputStream();
    try (java.io.OutputStream out = gg.tame.conduit.crypto.CipherStreams.encrypting(encryptedBytes, gg.tame.conduit.crypto.AesCfb8.encryptor(secret))) {
      MinecraftFrames.write(out, new byte[] {1, 2, 3});
    }
    try (java.io.InputStream in = gg.tame.conduit.crypto.CipherStreams.decrypting(new ByteArrayInputStream(encryptedBytes.toByteArray()), gg.tame.conduit.crypto.AesCfb8.decryptor(secret))) {
      require(java.util.Arrays.equals(MinecraftFrames.read(in, 16), new byte[] {1, 2, 3}), "encrypted packet framing round trip");
    }
    ProtocolDefinition protocol = ProtocolDefinition.forVersion(765);
    gg.tame.conduit.login.EncryptionHandshake handshake = new gg.tame.conduit.login.EncryptionHandshake(keys);
    gg.tame.conduit.login.EncryptionRequest request = gg.tame.conduit.login.EncryptionRequest.decode(protocol, handshake.request(protocol).encode(protocol));
    byte[] wrongToken = request.verifyToken().clone(); wrongToken[0] ^= 1;
    ByteArrayOutputStream bad = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bad)) {
      MinecraftOutput.varInt(output, 1);
      MinecraftOutput.bytes(output, gg.tame.conduit.crypto.RsaKeys.encrypt(keys.getPublic(), secret));
      MinecraftOutput.bytes(output, gg.tame.conduit.crypto.RsaKeys.encrypt(keys.getPublic(), wrongToken));
    }
    try { handshake.sharedSecret(protocol, bad.toByteArray()); throw new AssertionError("bad verify token accepted"); }
    catch (Exception expected) { }
    String hash = gg.tame.conduit.crypto.ServerHash.of("", secret, keys.getPublic());
    require(hash.equals(gg.tame.conduit.crypto.ServerHash.of("", secret, keys.getPublic())), "server hash must be deterministic");
  }
  private static void encryptionHelloLayout() throws Exception {
    ProtocolDefinition v765 = ProtocolDefinition.forVersion(765);
    ProtocolDefinition v776 = ProtocolDefinition.forVersion(776);
    require(!v765.loginShouldAuthenticate() && v776.loginShouldAuthenticate(), "should-authenticate is 26.2-only");
    require(v776.id(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_ENCRYPTION_REQUEST) == 1, "776 hello id");
    require(v765.id(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_ENCRYPTION_REQUEST) == 1, "765 hello id");
    require(!v776.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, 1, PacketKind.LOGIN_ENCRYPTION_REQUEST), "play id 1 is not hello");
    java.security.KeyPair keys = gg.tame.conduit.crypto.RsaKeys.generate();
    byte[] token = {9, 8, 7, 6};
    gg.tame.conduit.login.EncryptionRequest fixture = new gg.tame.conduit.login.EncryptionRequest("", keys.getPublic().getEncoded(), token, true);
    byte[] encoded765 = new gg.tame.conduit.login.EncryptionRequest("", keys.getPublic().getEncoded(), token, false).encode(v765);
    byte[] encoded776 = fixture.encode(v776);
    require(encoded776[0] == 1 && encoded765[0] == 1, "packet id first");
    require(encoded776.length == encoded765.length + 1, "776 adds should-authenticate boolean");
    require(encoded776[encoded776.length - 1] == 1, "should-authenticate true");
    gg.tame.conduit.login.EncryptionRequest decoded776 = gg.tame.conduit.login.EncryptionRequest.decode(v776, encoded776);
    require(decoded776.shouldAuthenticate() && java.util.Arrays.equals(decoded776.verifyToken(), token), "776 field order");
    gg.tame.conduit.login.EncryptionRequest decoded765 = gg.tame.conduit.login.EncryptionRequest.decode(v765, encoded765);
    require(!decoded765.shouldAuthenticate() && java.util.Arrays.equals(decoded765.verifyToken(), token), "765 has no boolean");
    try { gg.tame.conduit.login.EncryptionRequest.decode(v765, encoded776); throw new AssertionError("765 accepted 776 trailing boolean"); }
    catch (java.io.IOException expected) { }
    byte[] truncated = java.util.Arrays.copyOf(encoded776, encoded776.length - 1);
    try { gg.tame.conduit.login.EncryptionRequest.decode(v776, truncated); throw new AssertionError("truncated 776 hello accepted"); }
    catch (java.io.IOException expected) { }
  }
  private static void sessionAuthentication() throws Exception {
    HasJoinedResponse.Result parsed = HasJoinedResponse.parse("{\"id\":\"11111111222233334444555555555555\",\"name\":\"Notch\",\"properties\":[{\"name\":\"textures\",\"value\":\"val\",\"signature\":\"sig\"}]}");
    require(parsed.uniqueId().equals(HasJoinedResponse.uuid("11111111-2222-3333-4444-555555555555")) && parsed.username().equals("Notch") && parsed.properties().size() == 1, "hasJoined parse");
    try { HasJoinedResponse.parse("{\"name\":\"Notch\"}"); throw new AssertionError("missing id accepted"); }
    catch (gg.tame.conduit.auth.AuthenticationException expected) { }
    try { HasJoinedResponse.uuid("not-a-uuid"); throw new AssertionError("invalid uuid accepted"); }
    catch (gg.tame.conduit.auth.AuthenticationException expected) { }
    com.sun.net.httpserver.HttpServer http = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    http.createContext("/ok", exchange -> respond(exchange, 200, "{\"id\":\"000000000000000000000000000000aa\",\"name\":\"playr\",\"properties\":[]}"));
    http.createContext("/reject", exchange -> { exchange.sendResponseHeaders(204, -1); exchange.close(); });
    http.createContext("/slow", exchange -> { try { Thread.sleep(400); } catch (InterruptedException ignored) { } respond(exchange, 200, "{}"); });
    http.createContext("/bad", exchange -> respond(exchange, 200, "{"));
    http.start();
    int port = http.getAddress().getPort();
    gg.tame.conduit.config.AuthenticationSettings ok = new gg.tame.conduit.config.AuthenticationSettings(gg.tame.conduit.config.AuthenticationMode.ONLINE, "http://127.0.0.1:" + port + "/ok", 1000);
    PlayerProfile profile = new MojangSessionAuthenticator(ok).verify(new SessionQuery("playr", "abc", Optional.empty()));
    require(profile.authenticated() && profile.uniqueId().getLeastSignificantBits() == 0xaa, "successful session verification");
    try { new MojangSessionAuthenticator(new gg.tame.conduit.config.AuthenticationSettings(gg.tame.conduit.config.AuthenticationMode.ONLINE, "http://127.0.0.1:" + port + "/reject", 1000)).verify(new SessionQuery("playr", "abc", Optional.empty())); throw new AssertionError("rejection accepted"); }
    catch (gg.tame.conduit.auth.AuthenticationException expected) { }
    try { new MojangSessionAuthenticator(new gg.tame.conduit.config.AuthenticationSettings(gg.tame.conduit.config.AuthenticationMode.ONLINE, "http://127.0.0.1:" + port + "/slow", 100)).verify(new SessionQuery("playr", "abc", Optional.empty())); throw new AssertionError("timeout accepted"); }
    catch (gg.tame.conduit.auth.AuthenticationException expected) { }
    try { new MojangSessionAuthenticator(new gg.tame.conduit.config.AuthenticationSettings(gg.tame.conduit.config.AuthenticationMode.ONLINE, "http://127.0.0.1:" + port + "/bad", 1000)).verify(new SessionQuery("playr", "abc", Optional.empty())); throw new AssertionError("malformed body accepted"); }
    catch (gg.tame.conduit.auth.AuthenticationException expected) { }
    http.stop(0);
  }
  private static void onlineModeFeedsAuthenticatedIdentityToForwarding() throws Exception {
    Path secret = TempFiles.file("conduit-forwarding", ".secret"); Files.writeString(secret, "wire-secret");
    java.util.concurrent.atomic.AtomicReference<String> requestedHash = new java.util.concurrent.atomic.AtomicReference<>();
    com.sun.net.httpserver.HttpServer http = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    http.createContext("/session/minecraft/hasJoined", exchange -> {
      requestedHash.set(exchange.getRequestURI().getRawQuery());
      respond(exchange, 200, "{\"id\":\"000000000000000000000000000000aa\",\"name\":\"playr\",\"properties\":[{\"name\":\"textures\",\"value\":\"skin\"}]}");
    });
    http.start();
    try (ServerSocket backendListener = new ServerSocket(0)) {
      Thread backend = Thread.startVirtualThread(() -> {
        try (Socket socket = backendListener.accept()) {
          MinecraftFrames.read(socket.getInputStream(), 2048);
          MinecraftFrames.read(socket.getInputStream(), 2048);
          MinecraftFrames.write(socket.getOutputStream(), modernRequest(9, 1));
          byte[] response = MinecraftFrames.read(socket.getInputStream(), 2048);
          try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(response))) {
            MinecraftInput.varInt(input); MinecraftInput.varInt(input); input.readBoolean();
            input.readNBytes(32); byte[] signed = input.readAllBytes();
            try (DataInputStream payload = new DataInputStream(new ByteArrayInputStream(signed))) {
              MinecraftInput.varInt(payload); MinecraftInput.string(payload, 255);
              UUID uuid = new UUID(payload.readLong(), payload.readLong());
              require(uuid.equals(new UUID(0, 0xaa)), "forwarding used Login Start UUID instead of authenticated UUID");
              require(MinecraftInput.string(payload, 16).equals("playr"), "authenticated username");
              require(MinecraftInput.varInt(payload) == 1, "forwarded property count");
              require(MinecraftInput.string(payload, 64).equals("textures"), "forwarded textures name");
              require(MinecraftInput.string(payload, 32767).equals("skin"), "forwarded textures value");
              require(!payload.readBoolean(), "unsigned mock textures");
            }
          }
          MinecraftFrames.write(socket.getOutputStream(), new byte[] {2});
          MinecraftFrames.write(socket.getOutputStream(), new byte[] {2});
          drain(socket);
        } catch (Exception exception) { throw new RuntimeException(exception); }
      });
      gg.tame.conduit.config.AuthenticationSettings auth = new gg.tame.conduit.config.AuthenticationSettings(gg.tame.conduit.config.AuthenticationMode.ONLINE, "http://127.0.0.1:" + http.getAddress().getPort() + "/session/minecraft/hasJoined", 2000);
      ConduitConfiguration configuration = new ConduitConfiguration(new InetSocketAddress("127.0.0.1", reservePort()), 2048,
          ForwardingMode.MODERN, Optional.of(secret), List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", backendListener.getLocalPort()))), List.of("lobby"), List.of(), auth);
      java.security.KeyPair keys = gg.tame.conduit.crypto.RsaKeys.generate();
      try (MinecraftProxy proxy = new MinecraftProxy(configuration, new MojangSessionAuthenticator(auth), keys)) {
        int proxyPort = proxy.port();
        Thread serving = Thread.startVirtualThread(() -> { try { proxy.serve(); } catch (Exception ignored) { } });
        try (Socket client = new Socket("127.0.0.1", proxyPort)) {
          ProtocolDefinition protocol = ProtocolDefinition.forVersion(765);
          MinecraftFrames.write(client.getOutputStream(), new byte[] {0, (byte) 0xFD, 5, 5, 'l', 'o', 'c', 'a', 'l', 0x63, (byte) 0xDD, 2});
          MinecraftFrames.write(client.getOutputStream(), loginStart());
          gg.tame.conduit.login.EncryptionRequest request = gg.tame.conduit.login.EncryptionRequest.decode(protocol, MinecraftFrames.read(client.getInputStream(), 2048));
          byte[] shared = new byte[16]; new java.security.SecureRandom().nextBytes(shared);
          java.security.PublicKey publicKey = java.security.KeyFactory.getInstance("RSA").generatePublic(new java.security.spec.X509EncodedKeySpec(request.publicKey()));
          ByteArrayOutputStream response = new ByteArrayOutputStream();
          try (DataOutputStream output = new DataOutputStream(response)) {
            MinecraftOutput.varInt(output, 1);
            MinecraftOutput.bytes(output, gg.tame.conduit.crypto.RsaKeys.encrypt(publicKey, shared));
            MinecraftOutput.bytes(output, gg.tame.conduit.crypto.RsaKeys.encrypt(publicKey, request.verifyToken()));
          }
          MinecraftFrames.write(client.getOutputStream(), response.toByteArray());
          PacketTransport encrypted = new PacketTransport(
              gg.tame.conduit.crypto.CipherStreams.decrypting(client.getInputStream(), gg.tame.conduit.crypto.AesCfb8.decryptor(shared)),
              gg.tame.conduit.crypto.CipherStreams.encrypting(client.getOutputStream(), gg.tame.conduit.crypto.AesCfb8.encryptor(shared)));
          require(java.util.Arrays.equals(encrypted.read(2048), new byte[] {2}), "encrypted Login Success missing");
          byte[] next = encrypted.read(2048);
          if (next.length > 0 && next[0] == 0) next = encrypted.read(2048);
          require(java.util.Arrays.equals(next, new byte[] {2}), "encrypted Finish Configuration missing");
        }
        backend.join(); serving.interrupt();
      }
    }
    require(requestedHash.get() != null && requestedHash.get().contains("username=playr") && requestedHash.get().contains("serverId="), "session service was not queried");
    http.stop(0);
  }
  private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
  private static BackendLoginPipeline pipeline(Path secret) throws Exception {
    return new BackendLoginPipeline(ProtocolDefinition.forVersion(765), new ModernForwarder(ForwardingSecret.load(secret)),
        new PlayerProfile(new UUID(1, 2), "player", List.of(), false), InetAddress.getByName("127.0.0.1"), 1024);
  }
  private static byte[] hmac(String secret, byte[] signed) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")); return mac.doFinal(signed);
  }
  private static byte[] modernRequest(int messageId, int version) throws Exception { return pluginRequest(messageId, "velocity:player_info", new byte[] {(byte) version}); }
  private static byte[] pluginRequest(int messageId, String channel, byte[] data) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, 4); MinecraftOutput.varInt(output, messageId); MinecraftOutput.string(output, channel); output.write(data);
    }
    return bytes.toByteArray();
  }
  private static byte[] pluginBody(int messageId, String channel, byte[] data) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) { MinecraftOutput.varInt(output, messageId); MinecraftOutput.string(output, channel); output.write(data); }
    return bytes.toByteArray();
  }
  private interface IORunnable { void run() throws Exception; }
  private static void expectIO(IORunnable action, String message) {
    try { action.run(); throw new AssertionError(message); }
    catch (AssertionError error) { throw error; }
    catch (Exception expected) { if (!(expected instanceof java.io.IOException)) throw new AssertionError(message + ": " + expected); }
  }
  private static void drain(java.net.Socket socket) {
    try {
      while (!Thread.currentThread().isInterrupted()) MinecraftFrames.read(socket.getInputStream(), 2048);
    } catch (Exception ignored) { }
  }
  private static byte[] loginStart() { return new byte[] {0, 5, 'p', 'l', 'a', 'y', 'r', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}; }
  /** 1.20.4 Login Success for the same player: zero UUID, name, no properties. */
  private static byte[] loginSuccess() { return new byte[] {2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 5, 'p', 'l', 'a', 'y', 'r', 0}; }
  private static int reservePort() throws Exception { try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
  private static String configuration(String mode) { return "[listener]\nhost = \"127.0.0.1\"\nport = 25565\nmax-frame-bytes = 64\n[forwarding]\nmode = \"" + mode + "\"\n[servers.lobby]\nhost = \"127.0.0.1\"\nport = 25566\n[routing]\ninitial = [\"lobby\"]\nfallback = [\"lobby\"]\n"; }
  private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
