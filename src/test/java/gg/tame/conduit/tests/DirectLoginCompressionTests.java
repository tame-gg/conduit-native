package gg.tame.conduit.tests;

import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.config.ForwardingMode;
import gg.tame.conduit.network.MinecraftProxy;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PluginMessage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Locks the login of a same-version session whose backend compresses.
 *
 * <p>A real 1.20.4 client joining a 1.20.4 server through Conduit with forwarding {@code none} was
 * never logged in by Conduit at all. The backend's Set Compression went through to the client, whose
 * link Conduit never compresses, and Conduit kept reading both sockets as if nothing had changed. The
 * backend's Finish Configuration then parsed as a plugin message and ended the session, and the first
 * packet Conduit wrote itself after that reached the client as a "badly compressed packet".
 *
 * <p>The backend here speaks the compressed format with its own framing code, not Conduit's, so the
 * test shares no assumption with the proxy about how that format looks.
 */
public final class DirectLoginCompressionTests {
  private static final int THRESHOLD = 64;

  private DirectLoginCompressionTests() {}

  public static void main(String[] args) throws Exception {
    run();
  }

  public static void run() throws Exception {
    byte[] large = pluginMessage("conduit:test", new byte[200]);
    try (ServerSocket backendListener = new ServerSocket(0)) {
      AtomicReference<Throwable> backendFailure = new AtomicReference<>();
      Thread backend = Thread.startVirtualThread(() -> {
        try (Socket socket = backendListener.accept()) {
          InputStream in = socket.getInputStream();
          OutputStream out = socket.getOutputStream();
          MinecraftFrames.read(in, 4096);
          MinecraftFrames.read(in, 4096);
          MinecraftFrames.write(out, new byte[] {0x03, THRESHOLD});
          MinecraftFrames.write(out, compress(loginSuccess()));
          require(Arrays.equals(decompress(MinecraftFrames.read(in, 4096)), new byte[] {0x03}),
              "backend gets one Login Acknowledged, in the compressed format");
          MinecraftFrames.write(out, compress(pluginMessage("minecraft:brand", PluginMessage.brandPayload("vanilla"))));
          MinecraftFrames.write(out, compress(large));
          MinecraftFrames.write(out, compress(new byte[] {0x02}));
          require(Arrays.equals(decompress(MinecraftFrames.read(in, 4096)), new byte[] {0x02}),
              "backend gets the client's Finish Configuration, in the compressed format");
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
        try (Socket client = new Socket("127.0.0.1", proxy.port())) {
          client.setSoTimeout(5_000);
          InputStream in = client.getInputStream();
          OutputStream out = client.getOutputStream();
          MinecraftFrames.write(out, new byte[] {0, (byte) 0xFD, 5, 5, 'l', 'o', 'c', 'a', 'l', 0x63, (byte) 0xDD, 2});
          MinecraftFrames.write(out, new byte[] {0, 5, 'p', 'l', 'a', 'y', 'r', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
          byte[] first = MinecraftFrames.read(in, 4096);
          require(first[0] == 0x02, "the client's first packet is Login Success, not the backend's Set Compression (got id " + first[0] + ")");
          MinecraftFrames.write(out, new byte[] {0x03});
          byte[] brand = MinecraftFrames.read(in, 4096);
          require(brand[0] == 0x00 && PluginMessage.decodeBody(Arrays.copyOfRange(brand, 1, brand.length), 4096)
              .brandText().startsWith("vanilla"), "the client gets the backend's brand as an uncompressed packet");
          require(Arrays.equals(MinecraftFrames.read(in, 4096), large),
              "a packet the backend compressed reaches the client decompressed and unchanged");
          require(Arrays.equals(MinecraftFrames.read(in, 4096), new byte[] {0x02}), "the client gets Finish Configuration");
          MinecraftFrames.write(out, new byte[] {0x02});
          backend.join(5_000);
        }
        serving.interrupt();
      }
      if (backendFailure.get() != null) throw new AssertionError("mock backend: " + backendFailure.get(), backendFailure.get());
      require(!backend.isAlive(), "mock backend finished its login");
    }
    System.out.println("DirectLoginCompressionTests passed.");
  }

  /** The compressed format: uncompressed length then zlib, or length 0 then the packet below the threshold. */
  private static byte[] compress(byte[] packet) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    if (packet.length < THRESHOLD) {
      MinecraftOutput.varInt(out, 0);
      out.write(packet);
      return bytes.toByteArray();
    }
    MinecraftOutput.varInt(out, packet.length);
    Deflater deflater = new Deflater();
    deflater.setInput(packet);
    deflater.finish();
    byte[] buffer = new byte[1024];
    while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer));
    deflater.end();
    return bytes.toByteArray();
  }

  private static byte[] decompress(byte[] frame) throws Exception {
    DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame));
    int length = MinecraftInput.varInt(in);
    byte[] rest = in.readAllBytes();
    if (length == 0) return rest;
    Inflater inflater = new Inflater();
    inflater.setInput(rest);
    byte[] packet = new byte[length];
    require(inflater.inflate(packet) == length, "zlib body inflates to its declared length");
    inflater.end();
    return packet;
  }

  /** 1.20.4 Login Success: UUID, name, no properties. */
  private static byte[] loginSuccess() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    MinecraftOutput.varInt(out, 0x02);
    out.writeLong(0L);
    out.writeLong(0L);
    MinecraftOutput.string(out, "playr");
    MinecraftOutput.varInt(out, 0);
    return bytes.toByteArray();
  }

  /** 1.20.4 Configuration plugin message, id 0x00. */
  private static byte[] pluginMessage(String channel, byte[] data) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    MinecraftOutput.varInt(out, 0x00);
    MinecraftOutput.string(out, channel);
    out.write(data);
    return bytes.toByteArray();
  }

  private static int reservePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
