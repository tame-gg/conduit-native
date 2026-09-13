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
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AllTests {
  public static void main(String[] arguments) throws Exception {
    decodeFramesWithoutOverAllocation();
    enforceProtocolTransitions();
    validateIndependentConfiguration();
    decodeHandshakeAndSelectBackend();
    proxyRelaysHandshakeAndBackendData();
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
  private static void enforceProtocolTransitions() {
    ProtocolSession session = new ProtocolSession();
    session.acceptHandshake(1); require(session.state() == ConnectionState.STATUS, "status transition failed");
    try { session.acceptHandshake(2); throw new AssertionError("duplicate handshake accepted"); }
    catch (IllegalStateException expected) { }
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
    byte[] packet = new byte[] {0, (byte) 0xFE, 5, 5, 'l', 'o', 'c', 'a', 'l', 0x63, (byte) 0xDD, 2};
    Handshake handshake = Handshake.decode(packet);
    require(handshake.protocolVersion() == 766 && handshake.requestedHost().equals("local") && handshake.requestedPort() == 25565 && handshake.nextState() == 2, "handshake failed");
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
          MinecraftFrames.write(client.getOutputStream(), new byte[] {0, (byte) 0xFE, 5, 5, 'l', 'o', 'c', 'a', 'l', 0x63, (byte) 0xDD, 2});
          require(java.util.Arrays.equals(MinecraftFrames.read(client.getInputStream(), 128), new byte[] {1, 42}), "proxy did not relay backend packet");
        }
        backend.join(); serving.interrupt();
      }
    }
  }
  private static int reservePort() throws Exception { try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
  private static String configuration(String mode) { return "[listener]\nhost = \"127.0.0.1\"\nport = 25565\nmax-frame-bytes = 64\n[forwarding]\nmode = \"" + mode + "\"\n[servers.lobby]\nhost = \"127.0.0.1\"\nport = 25566\n[routing]\ninitial = [\"lobby\"]\nfallback = [\"lobby\"]\n"; }
  private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
