// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.config.ForwardingMode;
import gg.tame.conduit.config.OpsSettings;
import gg.tame.conduit.config.SecuritySettings;
import gg.tame.conduit.login.LoginStart;
import gg.tame.conduit.network.MinecraftProxy;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PluginMessage;
import gg.tame.conduit.protocol.ProtocolDefinition;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * What an unauthenticated peer can send before it is anybody: random bytes into every decoder that
 * reads them, and malformed, oversized, truncated, stalled and repeated packets against a real proxy
 * on loopback. Every one must be refused with an I/O error or a closed connection -- never an
 * unchecked exception, an allocation the peer did not pay for, a thread or slot left held, or a proxy
 * that stops answering. Deterministic: the random inputs come from fixed seeds.
 */
public final class MalformedInputTests {
  public static void main(String[] a) throws Exception { run(); }

  public static void run() throws Exception {
    decodersRefuseRandomBytesWithIoErrorsOnly();
    malformedVarIntsAndLengthsAreRefused();
    loginStartRefusesNamesNoPlayerCouldHave();
    deeplyNestedTextIsRefusedNotRecursedInto();
    theProxySurvivesHostileConnections();
    System.out.println("MalformedInputTests OK");
  }

  private static final int[] PROTOCOLS = { 5, 47, 340, 393, 754, 759, 760, 763, 764, 765, 767, 772 };

  /** Anything but an IOException out of a decoder fed garbage is a fault a peer can trigger at will. */
  private static void decodersRefuseRandomBytesWithIoErrorsOnly() throws Exception {
    Random random = new Random(0xC0DE);
    List<ProtocolDefinition> tables = new ArrayList<>();
    for (int protocol : PROTOCOLS) if (ProtocolDefinition.hasCodec(protocol)) tables.add(ProtocolDefinition.forVersion(protocol));
    require(!tables.isEmpty(), "some protocol tables to decode with");
    for (int round = 0; round < 20_000; round++) {
      byte[] bytes = new byte[random.nextInt(48)];
      random.nextBytes(bytes);
      // Half the inputs start like a real packet, so decoding gets past the first field.
      if (bytes.length > 2 && random.nextBoolean()) { bytes[0] = 0; bytes[1] = (byte) (random.nextInt(0x7f)); }
      expectIoOnly("Handshake", bytes, () -> Handshake.decode(bytes));
      ProtocolDefinition table = tables.get(random.nextInt(tables.size()));
      expectIoOnly("LoginStart " + table.version().number(), bytes, () -> LoginStart.decode(bytes, table));
      expectIoOnly("PluginMessage", bytes, () -> PluginMessage.decodeBody(bytes, 1 << 16));
      expectIoOnly("frame", bytes, () -> MinecraftFrames.read(new ByteArrayInputStream(bytes), 1 << 16));
    }
  }

  /**
   * Lengths are the peer's word. One past four usable bits in a VarInt's fifth byte, a sixth byte, a
   * negative length and a length past the limit are refused before anything is allocated for them.
   */
  private static void malformedVarIntsAndLengthsAreRefused() throws Exception {
    byte[][] refused = {
      { (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x10 },            // overflows an int
      { (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0 },  // six bytes
      { (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x0f },            // -1
      { (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x07 },            // 2^31 - 1
      { (byte) 0x81, (byte) 0x80, 0x04 },                                       // 65537, one past the limit
      { (byte) 0x80 },                                                          // truncated
    };
    for (byte[] frame : refused) {
      try { MinecraftFrames.read(new ByteArrayInputStream(frame), 1 << 16); throw new AssertionError("frame accepted: " + hex(frame)); }
      catch (IOException expected) { }
    }
    // A string whose declared length is negative, or larger than the packet around it.
    for (int declared : new int[] { -1, Integer.MIN_VALUE, 200, 1 << 20 }) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream out = new DataOutputStream(bytes)) {
        MinecraftOutput.varInt(out, 0);
        MinecraftOutput.varInt(out, 765);
        MinecraftOutput.varInt(out, declared);
        out.write(new byte[8]);
      }
      try { Handshake.decode(bytes.toByteArray()); throw new AssertionError("host length " + declared + " accepted"); }
      catch (IOException expected) { }
    }
  }

  /**
   * Any 16 bytes used to be a name. In offline mode that name is kept as the player's, and it carried
   * line breaks into the log, section signs into every message naming the player, and spaces no
   * command argument can match.
   */
  private static void loginStartRefusesNamesNoPlayerCouldHave() throws Exception {
    ProtocolDefinition v47 = ProtocolDefinition.forVersion(47);
    for (String name : List.of("", "two words", "line\nbreak", "tab\tbed", "colour\u00a7c", "null\u0000", "caf\u00e9", "del\u007f")) {
      try { LoginStart.decode(loginStartBody(name), v47); throw new AssertionError("name accepted: " + name.replace("\n", "\\n")); }
      catch (IOException expected) { require(!expected.getMessage().contains(name) || name.isEmpty(), "the refusal does not repeat the name"); }
    }
    byte[] invalidUtf8 = { 2, (byte) 0xc3, (byte) 0x28 };
    try { LoginStart.decode(invalidUtf8, v47); throw new AssertionError("a name that is not UTF-8 was accepted"); }
    catch (IOException expected) { }
    for (String name : List.of("Steve", "a", "_under_score_16_", ".bedrock", "x-y~z!")) {
      require(LoginStart.decode(loginStartBody(name), v47).username().equals(name), name + " is still a name");
    }
  }

  /**
   * The component parser and everything that walks its tree recurse once per level, so a kick reason
   * or status answer nested a few thousand levels deep overflowed the stack of the connection thread
   * reading it. Past the client's own limit of 512 levels the text is unreadable, as any other
   * malformed text is: carried as the literal string, never an error.
   */
  private static void deeplyNestedTextIsRefusedNotRecursedInto() throws Exception {
    String deep = "[".repeat(20_000) + "\"x\"" + "]".repeat(20_000);
    require(gg.tame.conduit.protocol.text.ComponentCodec.parseJson(deep) == null, "20,000 levels are not parsed");
    require(gg.tame.conduit.text.TextCodec.fromJson(deep).plain().equals(deep), "the text is kept as the literal string");
    gg.tame.conduit.protocol.text.ComponentCodec.jsonToNbtBytes(deep);
    String deepest = "{\"extra\":[".repeat(255) + "\"x\"" + "]}".repeat(255);
    require(gg.tame.conduit.protocol.text.ComponentCodec.parseJson(deepest) != null, "510 levels still parse");
    require(gg.tame.conduit.text.TextCodec.fromJson(deepest).plain().equals("x"), "and read as the text they hold");
  }

  /**
   * A real proxy against a barrage of hostile connections, from one address, with the bot filter off
   * so that every one of them reaches the decoders instead of being turned away at the door. It must
   * still answer a server-list ping afterwards, hold no connection slot for any of them, and never
   * log a fault that escaped its connection's handling.
   */
  private static void theProxySurvivesHostileConnections() throws Exception {
    PrintStream original = System.err;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(new TeeStream(original, captured), true, StandardCharsets.UTF_8));
    System.setProperty("conduit.loginDeadlineMillis", "2000");
    int deadPort;
    try (ServerSocket reserved = new ServerSocket(0)) { deadPort = reserved.getLocalPort(); }
    int listenPort;
    try (ServerSocket reserved = new ServerSocket(0)) { listenPort = reserved.getLocalPort(); }
    SecuritySettings defaults = SecuritySettings.defaults();
    SecuritySettings open = new SecuritySettings(
        new SecuritySettings.ThrottleSettings(false, 1_000, 1_000, 1_000, 32, 64, 5_000),
        new SecuritySettings.BotFilterSettings(false, 10, 500, 60_000, 60_000),
        defaults.channelGuard(), defaults.attackMode());
    OpsSettings ops = new OpsSettings(OpsSettings.CURRENT_SCHEMA, null, null, null, null, open, null, null, null);
    ConduitConfiguration configuration = new ConduitConfiguration(new InetSocketAddress("127.0.0.1", listenPort), 1 << 20,
        ForwardingMode.NONE, Optional.empty(), List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", deadPort))),
        List.of("lobby"), List.of(), gg.tame.conduit.config.AuthenticationSettings.offline(), Optional.empty(), ops);
    MinecraftProxy proxy = new MinecraftProxy(configuration,
        gg.tame.conduit.auth.Authenticators.create(gg.tame.conduit.config.AuthenticationSettings.offline()),
        gg.tame.conduit.crypto.RsaKeys.generate(), TempFiles.dir("conduit-malformed").resolve("plugins"));
    Thread serving = Thread.ofPlatform().daemon().name("test-malformed-serve").start(() -> {
      try { proxy.serve(); } catch (Exception ignored) { }
    });
    ExecutorService attackers = Executors.newFixedThreadPool(32);
    try {
      require(await(() -> { try { return proxy.port() > 0; } catch (IOException notYet) { return false; } }), "proxy listening");
      int port = proxy.port();
      require(statusAnswered(port), "the proxy answers a ping before the barrage");
      List<Future<?>> attacks = new ArrayList<>();
      Random random = new Random(0xBAD);
      for (int i = 0; i < 300; i++) {
        byte[] garbage = new byte[1 + random.nextInt(600)];
        random.nextBytes(garbage);
        attacks.add(attackers.submit(() -> exchange(port, garbage, 2_000)));
      }
      // Frames that announce more than the limit, a VarInt that never ends, and one that overflows.
      attacks.add(attackers.submit(() -> exchange(port, new byte[] { (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x07 }, 2_000)));
      attacks.add(attackers.submit(() -> exchange(port, new byte[] { (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 1 }, 2_000)));
      attacks.add(attackers.submit(() -> exchange(port, new byte[] { (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x10 }, 2_000)));
      // A client that announces a large frame and then trickles it, and one that sends half a handshake.
      attacks.add(attackers.submit(() -> trickle(port)));
      attacks.add(attackers.submit(() -> exchange(port, frame(handshake(765, 2)), 0, 3_000)));
      // Login with names no player could have, a handshake for a protocol nobody speaks, a transfer
      // intent from 1.20.4 (transfers came in 1.20.5, so it is malformed), a 1.20.5 transfer that is a
      // login and sends nothing more, two Login Starts in a row, and a login that asks for a server
      // that is down.
      for (String name : List.of("", "two words", "line\nbreak", "\u00a7cred")) {
        attacks.add(attackers.submit(() -> exchange(port, concat(frame(handshake(47, 2)), frame(loginStart(name))), 3_000)));
      }
      attacks.add(attackers.submit(() -> exchange(port, frame(handshake(999_999, 2)), 3_000)));
      attacks.add(attackers.submit(() -> exchange(port, frame(handshake(765, 3)), 3_000)));
      attacks.add(attackers.submit(() -> exchange(port, frame(handshake(766, 3)), 3_000)));
      attacks.add(attackers.submit(() -> exchange(port, concat(frame(handshake(47, 2)), frame(loginStart("Twice")), frame(loginStart("Twice"))), 5_000)));
      // Status abuse: a request twice before the ping, and a ping before any request.
      byte[] request = frame(new byte[] { 0 });
      byte[] ping = frame(new byte[] { 1, 0, 0, 0, 0, 0, 0, 0, 7 });
      attacks.add(attackers.submit(() -> exchange(port, concat(frame(handshake(47, 1)), request, request, ping), 3_000)));
      attacks.add(attackers.submit(() -> exchange(port, concat(frame(handshake(47, 1)), ping), 3_000)));
      for (int i = 0; i < 100; i++) attacks.add(attackers.submit(() -> exchange(port, concat(frame(handshake(47, 1)), request, ping), 3_000)));
      for (Future<?> attack : attacks) attack.get(30, TimeUnit.SECONDS);

      require(statusAnswered(port), "the proxy still answers a ping after the barrage");
      require(await(() -> proxy.activeConnections() == 0), "every hostile connection let its slot go, " + proxy.activeConnections() + " held");
      String log = captured.toString(StandardCharsets.UTF_8);
      require(!log.contains("unhandled fault"), "no fault escaped a connection's handling:\n" + excerpt(log, "unhandled fault"));
      require(!log.contains("line\nbreak") && !log.contains("\u00a7cred"), "no hostile name reached the log");
    } finally {
      attackers.shutdownNow();
      System.clearProperty("conduit.loginDeadlineMillis");
      System.setErr(original);
      proxy.close();
      serving.join(10_000);
    }
  }

  // --- the hostile side ----------------------------------------------------------------------

  /** Sends {@code bytes}, then reads until the proxy closes the connection or {@code timeoutMs} passes. */
  private static Void exchange(int port, byte[] bytes, int timeoutMs) throws IOException { return exchange(port, bytes, 0, timeoutMs); }
  private static Void exchange(int port, byte[] bytes, int keep, int timeoutMs) throws IOException {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(timeoutMs);
      try {
        socket.getOutputStream().write(bytes, 0, bytes.length - keep);
        socket.getOutputStream().flush();
      } catch (IOException closedFirst) { return null; }
      InputStream in = socket.getInputStream();
      byte[] sink = new byte[4096];
      try { while (in.read(sink) >= 0) { } } catch (IOException closedOrTimedOut) { }
    }
    return null;
  }

  /** Announces a 900 KB frame and sends a byte every 100 ms: the handshake deadline has to end it. */
  private static Void trickle(int port) throws Exception {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(5_000);
      OutputStream out = socket.getOutputStream();
      ByteArrayOutputStream length = new ByteArrayOutputStream();
      MinecraftOutput.varInt(new DataOutputStream(length), 900_000);
      out.write(length.toByteArray());
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
      while (System.nanoTime() < deadline) {
        try { out.write(0); out.flush(); } catch (IOException closed) { return null; }
        Thread.sleep(100);
      }
      throw new AssertionError("a trickled frame kept its connection past the handshake deadline");
    }
  }

  private static boolean statusAnswered(int port) {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(5_000);
      OutputStream out = socket.getOutputStream();
      out.write(concat(frame(handshake(47, 1)), frame(new byte[] { 0 })));
      out.flush();
      byte[] response = MinecraftFrames.read(socket.getInputStream(), 1 << 20);
      return response.length > 0 && response[0] == 0;
    } catch (IOException failed) {
      return false;
    }
  }

  private static byte[] handshake(int protocol, int next) {
    return rethrow(() -> {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream out = new DataOutputStream(bytes)) {
        MinecraftOutput.varInt(out, 0);
        MinecraftOutput.varInt(out, protocol);
        MinecraftOutput.string(out, "localhost");
        out.writeShort(25565);
        MinecraftOutput.varInt(out, next);
      }
      return bytes.toByteArray();
    });
  }

  private static byte[] loginStart(String name) {
    return rethrow(() -> {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      bytes.write(0);
      bytes.write(loginStartBody(name));
      return bytes.toByteArray();
    });
  }

  private static byte[] loginStartBody(String name) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) { MinecraftOutput.string(out, name); }
    return bytes.toByteArray();
  }

  private static byte[] frame(byte[] packet) {
    return rethrow(() -> {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      MinecraftFrames.write(bytes, packet);
      return bytes.toByteArray();
    });
  }

  private static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    for (byte[] part : parts) bytes.writeBytes(part);
    return bytes.toByteArray();
  }

  // --- plumbing ------------------------------------------------------------------------------

  private interface Decode { Object run() throws IOException; }
  private interface Build { byte[] run() throws IOException; }

  private static void expectIoOnly(String what, byte[] input, Decode decode) {
    try { decode.run(); }
    catch (IOException refused) { }
    catch (RuntimeException | Error escaped) {
      throw new AssertionError(what + " threw " + escaped + " on " + hex(input), escaped);
    }
  }

  private static byte[] rethrow(Build build) {
    try { return build.run(); } catch (IOException impossible) { throw new java.io.UncheckedIOException(impossible); }
  }

  private static String hex(byte[] bytes) {
    StringBuilder text = new StringBuilder();
    for (byte b : bytes) text.append(String.format("%02x", b));
    return text.toString();
  }

  private static String excerpt(String log, String needle) {
    int at = log.indexOf(needle);
    return at < 0 ? "" : log.substring(Math.max(0, at - 200), Math.min(log.length(), at + 2_000));
  }

  /** Keeps the proxy's own error output visible while the test reads it too. */
  private static final class TeeStream extends OutputStream {
    private final OutputStream first;
    private final ByteArrayOutputStream second;
    TeeStream(OutputStream first, ByteArrayOutputStream second) { this.first = first; this.second = second; }
    @Override public synchronized void write(int b) throws IOException { first.write(b); second.write(b); }
    @Override public synchronized void write(byte[] b, int off, int len) throws IOException { first.write(b, off, len); second.write(b, off, len); }
    @Override public void flush() throws IOException { first.flush(); }
  }

  private static boolean await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) return true;
      Thread.sleep(20);
    }
    return condition.getAsBoolean();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
