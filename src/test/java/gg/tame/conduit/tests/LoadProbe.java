// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.config.AuthenticationSettings;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.config.ForwardingMode;
import gg.tame.conduit.config.HealthSettings;
import gg.tame.conduit.config.OpsSettings;
import gg.tame.conduit.config.SecuritySettings;
import gg.tame.conduit.network.MinecraftProxy;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What a population of connected players costs this proxy, and whether they stay connected.
 *
 * <p>Not a test and not in {@link AllTests}: it takes minutes, it is a measurement rather than an
 * assertion, and what it measures -- threads, memory -- is the machine's as much as Conduit's.
 * {@code scripts/load-probe.ps1} runs it.
 *
 * <p>Everything the probe itself runs is on virtual threads, and each socket is read and written by
 * the one thread that owns it. So the probe's own clients and backend cost no platform threads and
 * the platform-thread count is Conduit's, which is the number this exists to report. Each fake
 * client holds a real session: 1.8.9, offline mode, the DIRECT path, no Via and no translation, so
 * what is measured is the connection machinery and not a codec.
 *
 * <p>Liveness is a keep-alive round trip -- backend to client and the client's answer back -- so a
 * "healthy" connection is one whose two relay directions are both still moving, not merely one
 * whose socket has not been closed yet.
 */
public final class LoadProbe {
  /** 1.8.9. Chosen for the DIRECT path: no translator, no Via, nothing between the two sockets. */
  private static final int PROTOCOL = 47;
  private static final int KEEP_ALIVE = 0x00;
  private static final long KEEP_ALIVE_PERIOD_MS = 1_000;

  public static void main(String[] arguments) throws Exception {
    // Via's scheduler threads are not daemons, so without this the probe prints its table and then
    // never exits -- the same reason AllTests stops Via in a finally.
    try { probe(arguments); } finally { gg.tame.conduit.viaversion.ConduitViaBootstrap.stop(); }
  }

  private static void probe(String[] arguments) throws Exception {
    List<Integer> populations = new ArrayList<>();
    long holdMs = 20_000;
    boolean verbose = false;
    String label = System.getProperty("conduit.probe.label", "current");
    for (int index = 0; index < arguments.length; index++) {
      switch (arguments[index]) {
        case "--clients" -> {
          for (String count : arguments[++index].split(",")) populations.add(Integer.parseInt(count.trim()));
        }
        case "--hold-seconds" -> holdMs = TimeUnit.SECONDS.toMillis(Long.parseLong(arguments[++index]));
        case "--label" -> label = arguments[++index];
        // So the runner script can compile the probe through scripts/test.ps1 without running a
        // population, and without running the whole suite to get a class path built.
        case "--compile-only" -> { return; }
        case "--verbose" -> verbose = true;
        default -> throw new IllegalArgumentException("unknown argument " + arguments[index]
            + " (expected --clients 100,500,1000 [--hold-seconds 20] [--label name] [--verbose])");
      }
    }
    if (populations.isEmpty()) populations = List.of(100, 500, 1000);

    // The table goes to the real stdout whatever happens to the proxy's own; a thousand joins is a
    // couple of thousand INFO lines, which is both unreadable and slow enough to distort the run.
    PrintStream report = System.out;
    report.println("Conduit load probe: " + label);
    report.println("  java " + System.getProperty("java.version") + " on " + System.getProperty("os.name")
        + ", " + Runtime.getRuntime().availableProcessors() + " processors");
    report.println("  " + PROTOCOL + " (1.8.9) DIRECT, offline mode, keep-alive every "
        + KEEP_ALIVE_PERIOD_MS + "ms, held " + TimeUnit.MILLISECONDS.toSeconds(holdMs) + "s");
    report.println();
    report.printf(Locale.ROOT, "%8s  %9s  %9s  %11s  %11s  %10s  %s%n",
        "clients", "joined", "healthy", "os-threads", "conduit-io", "heap-MiB", "committed-MiB");

    for (int population : populations) {
      Result result;
      if (verbose) {
        result = measure(population, holdMs);
      } else {
        System.setOut(quiet());
        try { result = measure(population, holdMs); } finally { System.setOut(report); }
      }
      report.printf(Locale.ROOT, "%8d  %9d  %9d  %11d  %11d  %10d  %s%n",
          population, result.joined, result.healthy, result.platformThreads, result.conduitIoThreads,
          result.heapBytes / (1024 * 1024),
          result.committedBytes < 0 ? "n/a" : String.valueOf(result.committedBytes / (1024 * 1024)));
      if (result.joined < population) {
        report.println("  stopped: only " + result.joined + " of " + population + " joined ("
            + result.failure + ")");
        break;
      }
    }
  }

  private static PrintStream quiet() {
    return new PrintStream(OutputStream.nullOutputStream(), false, java.nio.charset.StandardCharsets.UTF_8);
  }

  private record Result(int joined, int healthy, int platformThreads, int conduitIoThreads,
                        long heapBytes, long committedBytes, String failure) { }

  private static Result measure(int population, long holdMs) throws Exception {
    try (Backend backend = new Backend();
         Proxy proxy = new Proxy(backend.server())) {
      List<Client> clients = new ArrayList<>(population);
      String failure = "";
      try {
        for (int index = 0; index < population; index++) {
          try {
            clients.add(Client.join(proxy.port(), "probe" + index));
          } catch (IOException | RuntimeException refused) {
            failure = refused.getClass().getSimpleName() + ": " + refused.getMessage();
            break;
          }
          // Ramping rather than storming: this measures a steady population, and a thousand
          // simultaneous logins would measure the login path's own concurrency limit instead.
          if ((index & 15) == 15) Thread.sleep(20);
        }
        int joined = clients.size();
        Thread.sleep(holdMs);
        int healthy = 0;
        for (Client client : clients) if (client.healthy()) healthy++;
        // After the population is up and quiet, so that what is reported is what holding it costs.
        System.gc();
        Thread.sleep(250);
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        return new Result(joined, healthy, threads.getThreadCount(), conduitIoThreads(),
            ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), committedBytes(), failure);
      } finally {
        for (Client client : clients) client.close();
        // Let the proxy notice, so the next population does not start with the last one's sessions
        // still ending and its threads still counted.
        Thread.sleep(1_500);
      }
    }
  }

  /** Live platform threads Conduit started for connections; the number this probe exists to move. */
  private static int conduitIoThreads() {
    int count = 0;
    for (Thread thread : Thread.getAllStackTraces().keySet()) {
      String name = thread.getName();
      if (name.startsWith("conduit-io") || name.startsWith("conduit-read") || name.startsWith("conduit-select")) count++;
    }
    return count;
  }

  /**
   * Committed process memory, which is where a platform thread's stack shows up and the heap
   * reading does not, or -1 where the JDK does not offer it.
   */
  private static long committedBytes() {
    var operatingSystem = ManagementFactory.getOperatingSystemMXBean();
    if (operatingSystem instanceof com.sun.management.OperatingSystemMXBean extended) {
      return extended.getCommittedVirtualMemorySize();
    }
    return -1;
  }

  // --- the proxy under measurement ----------------------------------------------------------------

  private static final class Proxy implements AutoCloseable {
    private final MinecraftProxy proxy;
    private final Thread serving;
    Proxy(BackendServer backend) throws Exception {
      // The throttle and the bot filter judge a source by address, and every client here shares
      // 127.0.0.1: left on, they would refuse the population this exists to measure.
      SecuritySettings security = new SecuritySettings(
          new SecuritySettings.ThrottleSettings(false, 40, 1_000, 32, 32, 64, 5_000),
          new SecuritySettings.BotFilterSettings(false, 10, 30_000, 60_000, 60_000),
          null, null);
      OpsSettings ops = new OpsSettings(OpsSettings.CURRENT_SCHEMA, null,
          new HealthSettings(false, 10_000, 1_500, 3, 2), null, null, security, null, null, null);
      ConduitConfiguration configuration = new ConduitConfiguration(
          new InetSocketAddress("127.0.0.1", reservePort()), 1 << 20, ForwardingMode.NONE, Optional.empty(),
          List.of(backend), List.of(backend.name()), List.of(backend.name()),
          AuthenticationSettings.offline(), Optional.empty(), ops);
      proxy = new MinecraftProxy(configuration, gg.tame.conduit.auth.Authenticators.create(AuthenticationSettings.offline()),
          gg.tame.conduit.crypto.RsaKeys.generate(), TempFiles.dir("conduit-load-probe").resolve("plugins"));
      serving = Thread.ofPlatform().daemon().name("probe-serve").start(() -> {
        try { proxy.serve(); } catch (Exception ended) { }
      });
    }
    int port() throws IOException { return proxy.port(); }
    @Override public void close() throws Exception { proxy.close(); serving.join(15_000); }
    /** A free port, the way the tests find one: the configuration will not take 0. */
    private static int reservePort() throws IOException {
      try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }
  }

  // --- the probe's own ends, both on virtual threads ----------------------------------------------

  /** Accepts, finishes the login, then keeps asking each client for a keep-alive answer. */
  private static final class Backend implements AutoCloseable {
    private final ServerSocket listener;
    private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<>());
    Backend() throws IOException {
      listener = new ServerSocket(0);
      Thread.ofVirtual().name("probe-backend-accept").start(() -> {
        try {
          while (true) {
            Socket socket = listener.accept();
            sockets.add(socket);
            Thread.ofVirtual().start(() -> serve(socket));
          }
        } catch (IOException closed) { }
      });
    }
    BackendServer server() { return new BackendServer("lobby", new InetSocketAddress("127.0.0.1", listener.getLocalPort())); }
    /** One thread per connection, reading and writing the one socket, so no virtual thread here waits on another's. */
    private void serve(Socket socket) {
      try (socket) {
        socket.setTcpNoDelay(true);
        InputStream input = socket.getInputStream();
        Handshake handshake = Handshake.decode(MinecraftFrames.read(input, 4096));
        if (handshake.nextState() != 2) return;
        MinecraftFrames.read(input, 4096);
        MinecraftFrames.write(socket.getOutputStream(), loginSuccess());
        MinecraftFrames.write(socket.getOutputStream(), joinGame());
        socket.setSoTimeout(100);
        long next = System.nanoTime();
        int token = 0;
        while (true) {
          if (System.nanoTime() >= next) {
            MinecraftFrames.write(socket.getOutputStream(), keepAlive(++token));
            next = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(KEEP_ALIVE_PERIOD_MS);
          }
          try { MinecraftFrames.read(input, 1 << 20); } catch (SocketTimeoutException quiet) { }
        }
      } catch (IOException ended) { }
    }
    @Override public void close() throws IOException {
      listener.close();
      synchronized (sockets) { for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) { } }
    }
  }

  /** Logs in, then answers every keep-alive on the one thread that owns its socket. */
  private static final class Client implements AutoCloseable {
    private final Socket socket;
    private final AtomicInteger roundTrips = new AtomicInteger();
    private final AtomicLong lastKeepAliveAt = new AtomicLong();
    private volatile boolean ended;
    private Client(Socket socket) { this.socket = socket; }

    static Client join(int port, String name) throws IOException {
      Socket socket = new Socket();
      socket.connect(new InetSocketAddress("127.0.0.1", port), 10_000);
      socket.setTcpNoDelay(true);
      socket.setSoTimeout(20_000);
      MinecraftFrames.write(socket.getOutputStream(), new Handshake(PROTOCOL, "localhost", 25565, 2).encode());
      MinecraftFrames.write(socket.getOutputStream(), loginStart(name));
      Client client = new Client(socket);
      byte[] success = MinecraftFrames.read(socket.getInputStream(), 1 << 20);
      if (packetId(success) != 2) throw new IOException(name + " expected Login Success, got id " + packetId(success));
      Thread.ofVirtual().name("probe-client-" + name).start(client::play);
      return client;
    }

    private void play() {
      try {
        socket.setSoTimeout(5_000);
        InputStream input = socket.getInputStream();
        while (true) {
          byte[] packet = MinecraftFrames.read(input, 1 << 20);
          if (packetId(packet) != KEEP_ALIVE) continue;
          // Echoed back byte for byte, which is what the game does and what the proxy's latency
          // clock matches on.
          MinecraftFrames.write(socket.getOutputStream(), packet);
          lastKeepAliveAt.set(System.nanoTime());
          roundTrips.incrementAndGet();
        }
      } catch (IOException ended) { } finally { this.ended = true; }
    }

    /** Still connected, and both directions were moving within the last few keep-alive periods. */
    boolean healthy() {
      if (ended || socket.isClosed()) return false;
      if (roundTrips.get() < 2) return false;
      long since = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastKeepAliveAt.get());
      return since < KEEP_ALIVE_PERIOD_MS * 5;
    }

    @Override public void close() { try { socket.close(); } catch (IOException ignored) { } }
  }

  // --- 1.8.9 packets ------------------------------------------------------------------------------

  private static byte[] loginStart(String name) throws IOException {
    return packet(0, output -> MinecraftOutput.string(output, name));
  }
  private static byte[] loginSuccess() throws IOException {
    return packet(2, output -> {
      MinecraftOutput.string(output, new UUID(0, 1).toString());
      MinecraftOutput.string(output, "backend");
    });
  }
  private static byte[] joinGame() throws IOException {
    return packet(0x01, output -> {
      output.writeInt(1); output.writeByte(0); output.writeByte(0); output.writeByte(0); output.writeByte(20);
      MinecraftOutput.string(output, "flat"); output.writeBoolean(false);
    });
  }
  private static byte[] keepAlive(int token) throws IOException {
    return packet(KEEP_ALIVE, output -> MinecraftOutput.varInt(output, token));
  }
  private static int packetId(byte[] packet) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      return MinecraftInput.varInt(input);
    }
  }
  private interface Body { void write(DataOutputStream output) throws IOException; }
  private static byte[] packet(int id, Body body) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, id);
      body.write(output);
    }
    return bytes.toByteArray();
  }
}
