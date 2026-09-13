package gg.tame.conduit.tests;

import gg.tame.conduit.config.ConfigurationLoader;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.ProtocolSession;
import gg.tame.conduit.protocol.VarIntFrameDecoder;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.routing.BackendSelector;
import gg.tame.conduit.network.MinecraftProxy;
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
    rejectInvalidLoginPluginRequests();
    mockBackendModernForwardingWireExchange();
    System.out.println("All Conduit foundation tests passed.");
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
    pipeline.observe(PacketDirection.SERVER_TO_CLIENT, new byte[] {2});
    require(login.state() == ConnectionState.PLAY, "configuration to play failed");
    require(protocol.id(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_PLUGIN_REQUEST) == 4, "1.20.4 login plugin request id");
    require(protocol.id(ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, PacketKind.LOGIN_PLUGIN_RESPONSE) == 2, "1.20.4 login plugin response id");
    require(protocol.id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_FINISH) == 2, "1.20.4 finish configuration id");
  }
  private static void validateIndependentConfiguration() throws Exception {
    Path config = Files.createTempFile("conduit", ".toml");
    Files.writeString(config, configuration("none"));
    require(ConfigurationLoader.load(config).maxFrameBytes() == 64, "configuration did not load");
    Files.writeString(config, configuration("modern"));
    try { ConfigurationLoader.load(config); throw new AssertionError("modern mode accepted without secret"); }
    catch (IllegalArgumentException expected) { }
  }
  private static void decodeHandshakeAndSelectBackend() throws Exception {
    byte[] packet = new byte[] {0, (byte) 0xFD, 5, 5, 'l', 'o', 'c', 'a', 'l', 0x63, (byte) 0xDD, 2};
    Handshake handshake = Handshake.decode(packet);
    require(handshake.protocolVersion() == 765 && handshake.requestedHost().equals("local") && handshake.requestedPort() == 25565 && handshake.nextState() == 2, "handshake failed");
    Path config = Files.createTempFile("conduit", ".toml"); Files.writeString(config, configuration("none"));
    require(new BackendSelector(ConfigurationLoader.load(config)).candidates().getFirst().name().equals("lobby"), "backend selection failed");
  }
  private static void proxyRelaysHandshakeAndBackendData() throws Exception {
    try (ServerSocket backendListener = new ServerSocket(0)) {
      Thread backend = Thread.startVirtualThread(() -> {
        try (Socket socket = backendListener.accept()) {
          byte[] handshake = MinecraftFrames.read(socket.getInputStream(), 128);
          require(Handshake.decode(handshake).nextState() == 2, "proxy did not forward handshake");
          MinecraftFrames.write(socket.getOutputStream(), new byte[] {1, 42});
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
          require(java.util.Arrays.equals(MinecraftFrames.read(client.getInputStream(), 128), new byte[] {1, 42}), "proxy did not relay backend packet");
        }
        backend.join(); serving.interrupt();
      }
    }
  }
  private static void createAuthenticatedModernForwardingPayload() throws Exception {
    Path secret = Files.createTempFile("conduit-forwarding", ".secret"); Files.writeString(secret, "do-not-log-me");
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
    try { forwarder.payload(new ForwardingRequest(player, InetAddress.getByName("127.0.0.1"), 765, 4)); throw new AssertionError("unsupported forwarding version accepted"); }
    catch (IllegalArgumentException expected) { }
    Path empty = Files.createTempFile("conduit-empty", ".secret"); Files.writeString(empty, " \n");
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
    Path secret = Files.createTempFile("conduit-forwarding", ".secret"); Files.writeString(secret, "exchange-secret");
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
    try { pipeline(secret).onBackendPacket(modernRequest(1, 2), 1024); throw new AssertionError("unsupported forwarding version accepted"); }
    catch (java.io.IOException expected) { }
  }
  private static void rejectInvalidLoginPluginRequests() throws Exception {
    Path secret = Files.createTempFile("conduit-forwarding", ".secret"); Files.writeString(secret, "exchange-secret");
    byte[] valid = pipeline(secret).onBackendPacket(pluginRequest(3, "velocity:player_info", new byte[] {1}), 1024);
    require(valid != null && valid[1] == 3, "valid request id must be echoed");
    expectIO(() -> LoginPluginRequest.decode(pluginBody(-1, "velocity:player_info", new byte[] {1}), 1024), "negative message id accepted");
    expectIO(() -> pipeline(secret).onBackendPacket(pluginRequest(1, "minecraft:brand", new byte[] {1}), 1024), "unknown channel accepted");
    expectIO(() -> pipeline(secret).onBackendPacket(pluginRequest(1, "velocity:player_info", new byte[] {1, 2}), 1024), "malformed payload accepted");
    expectIO(() -> pipeline(secret).onBackendPacket(pluginRequest(1, "velocity:player_info", new byte[0]), 1024), "empty payload accepted");
    expectIO(() -> LoginPluginRequest.decode(pluginBody(1, "velocity:player_info", new byte[8]), 4), "oversized payload accepted");
    expectIO(() -> LoginPluginRequest.decode(new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80}, 1024), "malformed VarInt accepted");
    expectIO(() -> LoginPluginRequest.decode(new byte[] {(byte) 0x80}, 1024), "truncated packet accepted");
    expectIO(() -> pipeline(secret).onBackendPacket(new byte[] {1}, 1024), "encryption request accepted");
  }
  private static void mockBackendModernForwardingWireExchange() throws Exception {
    Path secret = Files.createTempFile("conduit-forwarding", ".secret"); Files.writeString(secret, "wire-secret");
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
          require(java.util.Arrays.equals(MinecraftFrames.read(client.getInputStream(), 2048), new byte[] {2}), "client did not receive Finish Configuration");
        }
        backend.join(); serving.interrupt();
      }
    }
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
  private static byte[] loginStart() { return new byte[] {0, 5, 'p', 'l', 'a', 'y', 'r', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}; }
  private static int reservePort() throws Exception { try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
  private static String configuration(String mode) { return "[listener]\nhost = \"127.0.0.1\"\nport = 25565\nmax-frame-bytes = 64\n[forwarding]\nmode = \"" + mode + "\"\n[servers.lobby]\nhost = \"127.0.0.1\"\nport = 25566\n[routing]\ninitial = [\"lobby\"]\nfallback = [\"lobby\"]\n"; }
  private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
