// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.config.ForwardingMode;
import gg.tame.conduit.config.HealthSettings;
import gg.tame.conduit.config.OpsSettings;
import gg.tame.conduit.config.SecuritySettings;
import gg.tame.conduit.forwarding.Forwarders;
import gg.tame.conduit.login.PlayerProfile;
import gg.tame.conduit.metrics.ConduitMetrics;
import gg.tame.conduit.network.MinecraftProxy;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.session.BackendConnection;
import gg.tame.conduit.network.PacketTransport;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketCompression;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.security.ConnectionThrottle;
import gg.tame.conduit.protocol.VarIntFrameDecoder;
import gg.tame.conduit.session.PlayerManager;
import gg.tame.conduit.session.TrackedPlayer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Lifecycle, timeout and framing regressions for many simultaneous players. */
public final class ConcurrencyTests {
  public static void main(String[] a) throws Exception { run(); }

  public static void run() throws Exception {
    rejectOverlongVarInts();
    reconnectKeepsTheLivePlayerIndexed();
    closingTheTransportClosesTheSocket();
    aProxyEndedSessionClosesTheClientSocket();
    aClientThatStopsReadingIsCutOff();
    aSlowButReadingClientIsNotCutOff();
    sessionThreadsStayOffTheWindowsPoller();
    aStalledLoginIsTimedOut();
    aBackendThatNeverFinishesLoginReleasesBothSockets();
    rapidServerCommandsOpenOneBackendConnection();
    aQuietBackendKeepsTheSwitchedPlayer();
    anUnreachableFirstServerIsExplained();
    closingABackendTwiceCountsOnce();
    manySessionsAllReleaseWhenTheirBackendsDrop();
    rejectHostileCompressedPackets();
    oneFailedAcceptDoesNotEndTheListener();
    aSourcesConcurrencyCapSurvivesAnEvictionStorm();
    aCrowdOfSessionsLeavesNothingBehind();
    framesAreNotAllocatedBeforeTheyArrive();
    everyBackendASessionOpensIsClosedWhenItEnds();
    System.out.println("ConcurrencyTests passed.");
  }

  /**
   * The frame length is the peer's word for it, taken before a single body byte has been read. An
   * unauthenticated connection that declares the limit and then sends nothing used to be handed a
   * full-size array for the asking, once per frame.
   */
  private static void framesAreNotAllocatedBeforeTheyArrive() throws Exception {
    int limit = 2 * 1024 * 1024;
    ByteArrayOutputStream declaration = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(declaration)) {
      MinecraftOutput.varInt(output, limit);
      output.write(new byte[12]);
    }
    byte[] hostile = declaration.toByteArray();
    com.sun.management.ThreadMXBean threads =
        java.lang.management.ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean
            && bean.isThreadAllocatedMemorySupported() ? bean : null;
    long before = threads == null ? 0 : threads.getCurrentThreadAllocatedBytes();
    for (int attempt = 0; attempt < 64; attempt++) {
      boolean refused = false;
      try { MinecraftFrames.read(new ByteArrayInputStream(hostile), limit); }
      catch (IOException truncated) { refused = true; }
      require(refused, "a frame that never arrives must end as a truncated read");
    }
    long allocated = threads == null ? 0 : threads.getCurrentThreadAllocatedBytes() - before;
    require(allocated < 64L * limit / 4,
        "a declared frame must not be allocated before it arrives: " + allocated + " bytes");

    // What a real peer sends still comes back byte for byte, on both sides of the growth boundary.
    for (int size : new int[] {0, 1, 100, 8191, 8192, 8193, 128 * 1024}) {
      byte[] body = new byte[size];
      for (int index = 0; index < size; index++) body[index] = (byte) (index * 31);
      ByteArrayOutputStream framed = new ByteArrayOutputStream();
      MinecraftFrames.write(framed, body);
      byte[] read = MinecraftFrames.read(new ByteArrayInputStream(framed.toByteArray()), limit);
      require(java.util.Arrays.equals(read, body), "a " + size + "-byte frame round trips unchanged");
    }
    // And a frame arriving in dribs and drabs is still assembled whole.
    byte[] body = new byte[40_000];
    for (int index = 0; index < body.length; index++) body[index] = (byte) index;
    ByteArrayOutputStream framed = new ByteArrayOutputStream();
    MinecraftFrames.write(framed, body);
    require(java.util.Arrays.equals(MinecraftFrames.read(new TrickleStream(framed.toByteArray(), 1_000), limit), body),
        "a frame delivered in pieces is assembled whole");
  }

  /**
   * Every backend a session opens is closed when the session ends, including one a switch installs
   * while the session is already closing. {@code close()} read the current backend and the switch
   * target one after the other, outside the lock the switch commits under, so a switch that landed
   * between those two reads left its connection open behind a player who had already gone. This is
   * an invariant test, not a reproduction of that interleaving: the clients disconnect across the
   * whole width of a switch, and afterwards every backend socket the proxy opened must be shut.
   */
  private static void everyBackendASessionOpensIsClosedWhenItEnds() throws Exception {
    int crowd = 16;
    AtomicInteger opened = new AtomicInteger();
    AtomicInteger closed = new AtomicInteger();
    try (ServerSocket lobby = new ServerSocket(0); ServerSocket arena = new ServerSocket(0)) {
      Thread lobbyThread = Thread.startVirtualThread(() -> countingBackend(lobby, opened, closed));
      Thread arenaThread = Thread.startVirtualThread(() -> countingBackend(arena, opened, closed));
      ConduitConfiguration configuration = configuration(
          List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", lobby.getLocalPort())),
              new BackendServer("arena", new InetSocketAddress("127.0.0.1", arena.getLocalPort()))),
          List.of("lobby"), List.of("lobby"),
          new SecuritySettings(new SecuritySettings.ThrottleSettings(true, 1_000, 60_000, 500, 32, 64, 5_000),
              SecuritySettings.BotFilterSettings.defaults(),
              SecuritySettings.ChannelGuardSettings.defaults(),
              SecuritySettings.AttackModeSettings.defaults()));
      int baseBackends = ConduitMetrics.current().activeBackends();
      try (MinecraftProxy proxy = new MinecraftProxy(configuration)) {
        Thread serving = Thread.startVirtualThread(() -> { try { proxy.serve(); } catch (Exception ignored) { } });
        List<Thread> sessions = new ArrayList<>();
        for (int index = 0; index < crowd; index++) {
          // Nought to fifteen milliseconds after asking to switch: before the commit, during it,
          // and after it, without depending on where any one of them lands.
          int leaveAfter = index;
          sessions.add(Thread.startVirtualThread(() -> {
            try (Socket client = new Socket("127.0.0.1", proxy.port())) {
              client.setSoTimeout(20_000);
              MinecraftFrames.write(client.getOutputStream(), new Handshake(47, "localhost", 25565, 2).encode());
              MinecraftFrames.write(client.getOutputStream(), legacyLoginStart());
              if (PlayPackets.packetId(MinecraftFrames.read(client.getInputStream(), 4096)) != 2) return;
              MinecraftFrames.write(client.getOutputStream(), legacyChat("/server arena"));
              Thread.sleep(leaveAfter);
            } catch (Exception ignored) { }
          }));
        }
        for (Thread session : sessions) session.join(25_000);
        long deadline = System.currentTimeMillis() + 25_000;
        while (System.currentTimeMillis() < deadline
            && (closed.get() < opened.get() || ConduitMetrics.current().activeBackends() != baseBackends)) {
          Thread.sleep(50);
        }
        require(opened.get() >= crowd, "every session reached a backend: " + opened.get());
        require(closed.get() == opened.get(),
            "every backend the sessions opened is closed: " + closed.get() + " of " + opened.get());
        require(ConduitMetrics.current().activeBackends() == baseBackends,
            "the live backend gauge is back to where it started: " + ConduitMetrics.current().activeBackends());
        require(proxy.activeConnections() == 0, "every worker returned: " + proxy.activeConnections());
        serving.interrupt();
      }
      lobbyThread.interrupt(); arenaThread.interrupt();
    }
  }

  /** Logs players in, counts the connections it was given, and counts the ones the proxy shuts. */
  private static void countingBackend(ServerSocket listener, AtomicInteger opened, AtomicInteger closed) {
    try {
      while (true) {
        Socket socket = listener.accept();
        Thread.startVirtualThread(() -> {
          try (socket) {
            MinecraftFrames.read(socket.getInputStream(), 4096);
            MinecraftFrames.read(socket.getInputStream(), 4096);
            opened.incrementAndGet();
            MinecraftFrames.write(socket.getOutputStream(), legacyLoginSuccess());
            MinecraftFrames.write(socket.getOutputStream(), legacyJoinGame());
            while (socket.getInputStream().read() >= 0) { }
            closed.incrementAndGet();
          } catch (Exception ended) { closed.incrementAndGet(); }
        });
      }
    } catch (Exception ignored) { }
  }

  /** Delivers a byte stream in small pieces, the way a socket hands over a large packet. */
  private static final class TrickleStream extends InputStream {
    private final byte[] data;
    private final int piece;
    private int position;
    private TrickleStream(byte[] data, int piece) { this.data = data; this.piece = piece; }
    @Override public int read() { return position < data.length ? data[position++] & 0xff : -1; }
    @Override public int read(byte[] buffer, int offset, int length) {
      if (position >= data.length) return -1;
      int count = Math.min(Math.min(piece, length), data.length - position);
      System.arraycopy(data, position, buffer, offset, count);
      position += count;
      return count;
    }
  }

  /**
   * Everything a hostile peer can put in the compressed-packet header. The size is a declaration,
   * not a measurement, and nothing that follows it may be trusted to agree with it.
   */
  private static void rejectHostileCompressedPackets() throws Exception {
    int limit = 2 * 1024 * 1024;

    // A stream asking for a preset dictionary: inflate makes no progress, is not finished, and does
    // not need input. The loop that waited for it to produce something spun a core forever.
    PacketCompression compression = compression(limit);
    byte[] dictionary = new byte[16];
    java.util.zip.Deflater deflater = new java.util.zip.Deflater();
    deflater.setDictionary(dictionary);
    byte[] payload = "abcdefghijabcdefghij".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    deflater.setInput(payload);
    deflater.finish();
    byte[] scratch = new byte[512];
    int compressed = deflater.deflate(scratch);
    deflater.end();
    byte[] withDictionary = declared(payload.length, scratch, compressed);
    require(completesWithin(() -> compression.unwrap(withDictionary), 5_000),
        "a stream that asks for a dictionary must be rejected, not spun on");

    // A size that overflows the int: 80 80 80 80 10 declared zero, which means "not compressed",
    // and the deflate stream behind it went upstream as a packet.
    byte[] overflowing = {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x10, 1, 2, 3};
    require(rejects(compression(limit), overflowing), "an overflowing declared size must be rejected");

    // Negative, and larger than the frame limit: both are refusals, not allocations.
    require(rejects(compression(limit), new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x0F, 1}),
        "a negative declared size must be rejected");
    ByteArrayOutputStream oversize = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(oversize)) { MinecraftOutput.varInt(output, limit + 1); output.write(new byte[] {1, 2, 3}); }
    require(rejects(compression(limit), oversize.toByteArray()), "a declared size past the frame limit must be rejected");

    // Twelve bytes claiming two megabytes. The declaration must not buy an allocation before the
    // stream has produced anything: a hundred of these used to be a hundred full-size arrays.
    ByteArrayOutputStream lying = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(lying)) {
      MinecraftOutput.varInt(output, limit);
      output.write(deflate(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}));
    }
    PacketCompression lyingSize = compression(limit);
    com.sun.management.ThreadMXBean threads =
        java.lang.management.ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean
            && bean.isThreadAllocatedMemorySupported() ? bean : null;
    long before = threads == null ? 0 : threads.getCurrentThreadAllocatedBytes();
    for (int attempt = 0; attempt < 64; attempt++) {
      require(rejects(lyingSize, lying.toByteArray()), "a size the stream cannot produce must be rejected");
    }
    long allocated = threads == null ? 0 : threads.getCurrentThreadAllocatedBytes() - before;
    require(allocated < 64L * limit / 4,
        "a declared size must not be allocated before the stream produces it: " + allocated + " bytes");

    // A packet that declares less than it carries: forwarding the first N bytes of it would leave
    // the reader parsing the middle of somebody else's packet.
    byte[] body = new byte[4096];
    for (int index = 0; index < body.length; index++) body[index] = (byte) index;
    ByteArrayOutputStream understated = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(understated)) {
      MinecraftOutput.varInt(output, 8);
      output.write(deflate(body));
    }
    require(rejects(compression(limit), understated.toByteArray()), "a packet longer than it declared must be rejected");

    // And a legitimate round trip still works, on both sides of the threshold.
    PacketCompression healthy = compression(limit);
    byte[] small = new byte[] {0x17, 1, 2, 3};
    require(java.util.Arrays.equals(healthy.unwrap(healthy.wrap(small)), small), "a sub-threshold packet round trips");
    require(java.util.Arrays.equals(healthy.unwrap(healthy.wrap(body)), body), "a compressed packet round trips");
  }

  /**
   * A listener that dies on one bad connection takes every future join with it. The accept that
   * fails here is injected, because the real causes — a peer that resets between the SYN and the
   * accept, a momentary descriptor shortage — are not something a test can ask the OS for.
   */
  private static void oneFailedAcceptDoesNotEndTheListener() throws Exception {
    try (ServerSocket lobby = new ServerSocket(0)) {
      Thread backend = Thread.startVirtualThread(() -> {
        try (Socket socket = lobby.accept()) {
          MinecraftFrames.read(socket.getInputStream(), 4096);
          MinecraftFrames.read(socket.getInputStream(), 4096);
          MinecraftFrames.write(socket.getOutputStream(), legacyLoginSuccess());
          MinecraftFrames.write(socket.getOutputStream(), legacyJoinGame());
          socket.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
        } catch (Exception ignored) { }
      });
      ConduitConfiguration configuration = configuration(
          List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", lobby.getLocalPort()))),
          List.of("lobby"), List.of(), SecuritySettings.defaults());
      FailingOnceChannel listener = new FailingOnceChannel(ServerSocketChannel.open());
      try (MinecraftProxy proxy = new MinecraftProxy(configuration,
          gg.tame.conduit.auth.Authenticators.create(configuration.authentication()),
          gg.tame.conduit.crypto.RsaKeys.generate(), java.nio.file.Path.of("plugins"), listener)) {
        Thread serving = Thread.startVirtualThread(() -> { try { proxy.serve(); } catch (Exception ignored) { } });
        try (Socket client = new Socket("127.0.0.1", proxy.port())) {
          client.setSoTimeout(15_000);
          MinecraftFrames.write(client.getOutputStream(), new Handshake(47, "localhost", 25565, 2).encode());
          MinecraftFrames.write(client.getOutputStream(), legacyLoginStart());
          require(PlayPackets.packetId(MinecraftFrames.read(client.getInputStream(), 4096)) == 2,
              "a player still joins after an accept failed");
        }
        require(listener.failures.get() == 1, "the injected accept failure was consumed");
        serving.interrupt();
      }
      backend.interrupt();
    }
  }

  /**
   * The per-source concurrency cap is what limits one address during a flood, and a flood is
   * exactly when the table it lives in is being churned by fresh addresses. Dropping a source's
   * window while its connections are still open let it start counting from zero again.
   */
  private static void aSourcesConcurrencyCapSurvivesAnEvictionStorm() throws Exception {
    ConnectionThrottle throttle = new ConnectionThrottle(
        new SecuritySettings.ThrottleSettings(true, 100_000, 60_000, 2, 32, 64, 5_000));
    InetAddress source = InetAddress.getByAddress(new byte[] {10, 0, 0, 1});
    ConnectionThrottle.LeaseHolder first = new ConnectionThrottle.LeaseHolder();
    ConnectionThrottle.LeaseHolder second = new ConnectionThrottle.LeaseHolder();
    require(throttle.tryAdmit(source, first) == ConnectionThrottle.Decision.ALLOW, "first connection admitted");
    require(throttle.tryAdmit(source, second) == ConnectionThrottle.Decision.ALLOW, "second connection admitted");
    require(throttle.tryAdmit(source, new ConnectionThrottle.LeaseHolder()) == ConnectionThrottle.Decision.THROTTLED,
        "the third is over the cap");
    // 9000 addresses arrive and leave: more than the table holds, so something has to go.
    for (int index = 0; index < 9_000; index++) {
      InetAddress flood = InetAddress.getByAddress(
          new byte[] {(byte) 172, (byte) (index >> 16), (byte) (index >> 8), (byte) index});
      ConnectionThrottle.LeaseHolder holder = new ConnectionThrottle.LeaseHolder();
      throttle.tryAdmit(flood, holder);
      throttle.release(holder.lease);
    }
    require(throttle.tryAdmit(source, new ConnectionThrottle.LeaseHolder()) == ConnectionThrottle.Decision.THROTTLED,
        "the cap must survive the storm that made it necessary");
    require(throttle.inFlight() == 2, "only the two held connections are in flight: " + throttle.inFlight());
    throttle.release(first.lease);
    throttle.release(second.lease);
    require(throttle.inFlight() == 0, "released connections are not in flight");
    require(throttle.tryAdmit(source, new ConnectionThrottle.LeaseHolder()) == ConnectionThrottle.Decision.ALLOW,
        "the source is admitted again once its connections end");
  }

  /**
   * Everything at once, on one proxy: simultaneous logins, switches, a backend that drops players
   * mid-session, and clients that vanish in the middle of a switch. Afterwards nothing may be left
   * holding anything — no worker, no socket, no index entry, no throttle lease.
   */
  private static void aCrowdOfSessionsLeavesNothingBehind() throws Exception {
    int crowd = 24;
    List<Socket> clients = new ArrayList<>();
    try (ServerSocket lobby = new ServerSocket(0); ServerSocket arena = new ServerSocket(0); ServerSocket flaky = new ServerSocket(0)) {
      Thread lobbyThread = Thread.startVirtualThread(() -> serveLegacyBackend(lobby, 0));
      Thread arenaThread = Thread.startVirtualThread(() -> serveLegacyBackend(arena, 0));
      // Completes the login and then drops the player: the fallback has to carry them.
      Thread flakyThread = Thread.startVirtualThread(() -> serveLegacyBackend(flaky, 300));
      ConduitConfiguration configuration = configuration(
          List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", lobby.getLocalPort())),
              new BackendServer("arena", new InetSocketAddress("127.0.0.1", arena.getLocalPort())),
              new BackendServer("flaky", new InetSocketAddress("127.0.0.1", flaky.getLocalPort()))),
          List.of("lobby"), List.of("lobby", "arena"),
          new SecuritySettings(new SecuritySettings.ThrottleSettings(true, 1_000, 60_000, 500, 32, 64, 5_000),
              SecuritySettings.BotFilterSettings.defaults(),
              SecuritySettings.ChannelGuardSettings.defaults(),
              SecuritySettings.AttackModeSettings.defaults()));
      int basePlayers = ConduitMetrics.current().activePlayers();
      int baseBackends = ConduitMetrics.current().activeBackends();
      try (MinecraftProxy proxy = new MinecraftProxy(configuration)) {
        Thread serving = Thread.startVirtualThread(() -> { try { proxy.serve(); } catch (Exception ignored) { } });
        AtomicInteger loggedIn = new AtomicInteger();
        List<Thread> sessions = new ArrayList<>();
        int firstLogin = LOGINS.get();
        for (int index = 0; index < crowd; index++) {
          int role = index % 3;
          Socket client = new Socket("127.0.0.1", proxy.port());
          synchronized (clients) { clients.add(client); }
          sessions.add(Thread.startVirtualThread(() -> {
            try (client) {
              client.setSoTimeout(20_000);
              MinecraftFrames.write(client.getOutputStream(), new Handshake(47, "localhost", 25565, 2).encode());
              MinecraftFrames.write(client.getOutputStream(), legacyLoginStart());
              if (PlayPackets.packetId(MinecraftFrames.read(client.getInputStream(), 4096)) != 2) return;
              loggedIn.incrementAndGet();
              switch (role) {
                // Switches, plays for a moment, then leaves.
                case 0 -> {
                  MinecraftFrames.write(client.getOutputStream(), legacyChat("/server arena"));
                  Thread.sleep(600);
                }
                // Leaves in the middle of its own switch.
                case 1 -> {
                  MinecraftFrames.write(client.getOutputStream(), legacyChat("/server arena"));
                  Thread.sleep(20);
                }
                // Goes to the backend that drops it, and is carried by the fallback.
                default -> {
                  MinecraftFrames.write(client.getOutputStream(), legacyChat("/server flaky"));
                  Thread.sleep(900);
                }
              }
            } catch (Exception ignored) { }
          }));
        }
        for (Thread session : sessions) session.join(25_000);
        require(loggedIn.get() == crowd, "every simultaneous login completes: " + loggedIn.get());
        long deadline = System.currentTimeMillis() + 25_000;
        while (System.currentTimeMillis() < deadline && !quiet(proxy, basePlayers, baseBackends)) Thread.sleep(50);
        require(proxy.activeConnections() == 0, "every worker returned: " + proxy.activeConnections());
        require(ConduitMetrics.current().activePlayers() == basePlayers,
            "every player slot is released: " + ConduitMetrics.current().activePlayers());
        require(ConduitMetrics.current().activeBackends() == baseBackends,
            "every backend connection is released: " + ConduitMetrics.current().activeBackends());
        require(proxy.runtime().playerManager().all().isEmpty(),
            "the player index is empty: " + proxy.runtime().playerManager().all().size());
        for (int login = firstLogin + 1; login <= LOGINS.get(); login++) {
          require(proxy.runtime().playerManager().getByUsername("playr" + login).isEmpty(), "no name is left indexed: playr" + login);
        }
        require(proxy.runtime().security().throttle().inFlight() == 0,
            "every throttle lease is released: " + proxy.runtime().security().throttle().inFlight());
        serving.interrupt();
      }
      lobbyThread.interrupt(); arenaThread.interrupt(); flakyThread.interrupt();
    } finally {
      synchronized (clients) { for (Socket socket : clients) try { socket.close(); } catch (IOException ignored) { } }
    }
  }

  private static boolean quiet(MinecraftProxy proxy, int basePlayers, int baseBackends) {
    return proxy.activeConnections() == 0
        && ConduitMetrics.current().activePlayers() == basePlayers
        && ConduitMetrics.current().activeBackends() == baseBackends
        && proxy.runtime().security().throttle().inFlight() == 0;
  }

  /** A 1.8 backend that logs a player in and, when asked, drops them again afterwards. */
  private static void serveLegacyBackend(ServerSocket listener, int dropAfterMillis) {
    try {
      while (true) {
        Socket socket = listener.accept();
        Thread.startVirtualThread(() -> {
          try (socket) {
            MinecraftFrames.read(socket.getInputStream(), 4096);
            MinecraftFrames.read(socket.getInputStream(), 4096);
            MinecraftFrames.write(socket.getOutputStream(), legacyLoginSuccess());
            MinecraftFrames.write(socket.getOutputStream(), legacyJoinGame());
            if (dropAfterMillis > 0) { Thread.sleep(dropAfterMillis); return; }
            socket.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
          } catch (Exception ignored) { }
        });
      }
    } catch (Exception ignored) { }
  }

  private static PacketCompression compression(int limit) {
    PacketCompression compression = new PacketCompression(limit);
    compression.enable(256);
    return compression;
  }

  private static byte[] declared(int size, byte[] compressed, int length) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, size);
      output.write(compressed, 0, length);
    }
    return bytes.toByteArray();
  }

  private static byte[] deflate(byte[] data) {
    java.util.zip.Deflater deflater = new java.util.zip.Deflater();
    deflater.setInput(data);
    deflater.finish();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    byte[] buffer = new byte[1024];
    while (!deflater.finished()) bytes.write(buffer, 0, deflater.deflate(buffer));
    deflater.end();
    return bytes.toByteArray();
  }

  private static boolean rejects(PacketCompression compression, byte[] packet) {
    try { compression.unwrap(packet); return false; }
    catch (IOException expected) { return true; }
  }

  private static boolean completesWithin(Attempt attempt, int millis) throws Exception {
    Thread worker = Thread.startVirtualThread(() -> { try { attempt.run(); } catch (Exception ignored) { } });
    worker.join(millis);
    return !worker.isAlive();
  }

  private interface Attempt { void run() throws Exception; }

  /** A listener whose first accept fails the way a reset connection or a spent descriptor does. */
  private static final class FailingOnceChannel extends java.nio.channels.ServerSocketChannel {
    private final java.nio.channels.ServerSocketChannel delegate;
    private final AtomicInteger failures = new AtomicInteger();
    private FailingOnceChannel(java.nio.channels.ServerSocketChannel delegate) {
      super(delegate.provider());
      this.delegate = delegate;
    }
    @Override public java.nio.channels.SocketChannel accept() throws IOException {
      if (failures.compareAndSet(0, 1)) throw new IOException("injected accept failure");
      return delegate.accept();
    }
    @Override public java.nio.channels.ServerSocketChannel bind(java.net.SocketAddress local, int backlog) throws IOException {
      delegate.bind(local, backlog); return this;
    }
    @Override public <T> java.nio.channels.ServerSocketChannel setOption(java.net.SocketOption<T> name, T value) throws IOException {
      delegate.setOption(name, value); return this;
    }
    @Override public <T> T getOption(java.net.SocketOption<T> name) throws IOException { return delegate.getOption(name); }
    @Override public java.util.Set<java.net.SocketOption<?>> supportedOptions() { return delegate.supportedOptions(); }
    @Override public ServerSocket socket() { return delegate.socket(); }
    @Override public java.net.SocketAddress getLocalAddress() throws IOException { return delegate.getLocalAddress(); }
    @Override protected void implCloseSelectableChannel() throws IOException { delegate.close(); }
    @Override protected void implConfigureBlocking(boolean block) throws IOException { delegate.configureBlocking(block); }
  }

  /**
   * A lost backend is closed where it is lost and again by the session that owned it. Counted
   * twice, the live-backend gauge walked below the connections that were still open.
   */
  private static void closingABackendTwiceCountsOnce() throws Exception {
    try (ServerSocket listener = new ServerSocket(0)) {
      Thread accepting = Thread.startVirtualThread(() -> {
        try { while (true) listener.accept(); } catch (Exception ignored) { }
      });
      int baseline = ConduitMetrics.current().activeBackends();
      BackendServer server = new BackendServer("lobby", new InetSocketAddress("127.0.0.1", listener.getLocalPort()));
      ConduitConfiguration configuration = configuration(List.of(server), List.of("lobby"), List.of(), SecuritySettings.defaults());
      BackendConnection first = new BackendConnection(server, BackendConnection.open(server),
          ProtocolDefinition.forVersion(47), Forwarders.create(configuration),
          new PlayerProfile(UUID.randomUUID(), "playr", List.of(), false),
          InetAddress.getLoopbackAddress(), configuration, false);
      BackendConnection second = new BackendConnection(server, BackendConnection.open(server),
          ProtocolDefinition.forVersion(47), Forwarders.create(configuration),
          new PlayerProfile(UUID.randomUUID(), "playr", List.of(), false),
          InetAddress.getLoopbackAddress(), configuration, false);
      require(ConduitMetrics.current().activeBackends() == baseline + 2, "two backends are open");
      first.close();
      first.close();
      require(ConduitMetrics.current().activeBackends() == baseline + 1,
          "the second close must not discount a live backend: " + ConduitMetrics.current().activeBackends());
      second.close();
      accepting.interrupt();
    }
  }

  /**
   * Many players at once, every one of them ended by the proxy rather than by the client. Each has
   * to give back its player slot and its backend connection without the client's cooperation: a
   * session that only stops when the far end hangs up is a thread, a socket and a throttle lease
   * that a disappearing backend can strand by the hundred.
   */
  private static void manySessionsAllReleaseWhenTheirBackendsDrop() throws Exception {
    int players = 20;
    List<Socket> clients = new ArrayList<>();
    try (ServerSocket lobby = new ServerSocket(0)) {
      Thread accepting = Thread.startVirtualThread(() -> {
        try {
          while (true) {
            Socket socket = lobby.accept();
            Thread.startVirtualThread(() -> {
              try (socket) {
                MinecraftFrames.read(socket.getInputStream(), 4096);
                MinecraftFrames.read(socket.getInputStream(), 4096);
                MinecraftFrames.write(socket.getOutputStream(), legacyLoginSuccess());
                MinecraftFrames.write(socket.getOutputStream(), legacyJoinGame());
              } catch (Exception ignored) { }
            });
          }
        } catch (Exception ignored) { }
      });
      ConduitConfiguration configuration = configuration(
          List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", lobby.getLocalPort()))),
          List.of("lobby"), List.of(), SecuritySettings.defaults());
      int basePlayers = ConduitMetrics.current().activePlayers();
      int baseBackends = ConduitMetrics.current().activeBackends();
      try (MinecraftProxy proxy = new MinecraftProxy(configuration)) {
        Thread serving = Thread.startVirtualThread(() -> { try { proxy.serve(); } catch (Exception ignored) { } });
        List<Thread> joining = new ArrayList<>();
        AtomicInteger loggedIn = new AtomicInteger();
        for (int index = 0; index < players; index++) {
          Socket client = new Socket("127.0.0.1", proxy.port());
          synchronized (clients) { clients.add(client); }
          joining.add(Thread.startVirtualThread(() -> {
            try {
              client.setSoTimeout(20_000);
              MinecraftFrames.write(client.getOutputStream(), new Handshake(47, "localhost", 25565, 2).encode());
              MinecraftFrames.write(client.getOutputStream(), legacyLoginStart());
              if (PlayPackets.packetId(MinecraftFrames.read(client.getInputStream(), 4096)) == 2) loggedIn.incrementAndGet();
            } catch (Exception ignored) { }
          }));
        }
        for (Thread thread : joining) thread.join(20_000);
        require(loggedIn.get() == players, "every simultaneous login completes: " + loggedIn.get());
        // The clients are still holding their sockets open; only the proxy can end these sessions.
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline
            && (ConduitMetrics.current().activePlayers() > basePlayers
                || ConduitMetrics.current().activeBackends() > baseBackends)) {
          Thread.sleep(50);
        }
        require(ConduitMetrics.current().activePlayers() == basePlayers,
            "every ended session releases its player slot: " + ConduitMetrics.current().activePlayers());
        require(ConduitMetrics.current().activeBackends() == baseBackends,
            "every ended session releases its backend: " + ConduitMetrics.current().activeBackends());
        serving.interrupt();
      }
      accepting.interrupt();
    } finally {
      synchronized (clients) { for (Socket socket : clients) try { socket.close(); } catch (IOException ignored) { } }
    }
  }

  /**
   * A fifth VarInt byte carries four usable bits. {@code 80 80 80 80 10} shifts its only set bit
   * off the end of the int and was read as a length of zero: the frame after it was consumed as
   * somebody else's body and both ends of the session then disagreed about where packets start.
   */
  private static void rejectOverlongVarInts() throws Exception {
    byte[] overlong = {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x10};
    boolean framesRejected = false;
    try { MinecraftFrames.read(new ByteArrayInputStream(overlong), 4096); }
    catch (IOException expected) { framesRejected = true; }
    require(framesRejected, "MinecraftFrames must reject a VarInt whose fifth byte overflows");

    boolean decoderRejected = false;
    try { new VarIntFrameDecoder(4096).tryDecode(ByteBuffer.wrap(overlong)); }
    catch (IllegalArgumentException expected) { decoderRejected = true; }
    require(decoderRejected, "VarIntFrameDecoder must reject a VarInt whose fifth byte overflows");

    boolean inputRejected = false;
    try { MinecraftInput.varInt(new DataInputStream(new ByteArrayInputStream(overlong))); }
    catch (IOException expected) { inputRejected = true; }
    require(inputRejected, "MinecraftInput must reject a VarInt whose fifth byte overflows");

    // -1 is five bytes ending in 0x0F and stays readable.
    byte[] negative = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x0F};
    require(MinecraftInput.varInt(new DataInputStream(new ByteArrayInputStream(negative))) == -1,
        "a legal five-byte -1 must still decode");
  }

  /**
   * A client that reconnects before its previous session has noticed the socket is gone is indexed
   * twice under one UUID. Removing the dead session then took the live one's name with it, and
   * {@code /find}, {@code /send} and every plugin lookup by name reported the player offline while
   * they were standing in a world.
   */
  private static void reconnectKeepsTheLivePlayerIndexed() {
    PlayerManager players = new PlayerManager();
    UUID id = UUID.randomUUID();
    TrackedPlayer stale = new StubPlayer(id, "playr");
    TrackedPlayer live = new StubPlayer(id, "playr");
    players.add(stale);
    players.add(live);
    players.remove(stale);
    require(players.get(id).orElse(null) == live, "the live session keeps the UUID index");
    require(players.getByUsername("playr").orElse(null) == live, "the live session keeps the name index");
  }

  /**
   * {@link PacketTransport#close()} is the only thing the session calls when it ends a connection
   * itself. Leaving the socket open leaves the client reader thread parked in a read that nothing
   * will ever complete, holding its connection slot and its throttle lease with it.
   */
  private static void closingTheTransportClosesTheSocket() throws Exception {
    try (ServerSocket listener = new ServerSocket(0);
         Socket client = new Socket("127.0.0.1", listener.getLocalPort());
         Socket accepted = listener.accept()) {
      PacketTransport transport = new PacketTransport(accepted);
      Thread reader = Thread.startVirtualThread(() -> {
        try { transport.read(4096); } catch (Exception ignored) { }
      });
      Thread.sleep(100);
      transport.close();
      reader.join(5_000);
      require(!reader.isAlive(), "closing the transport must unblock a reader parked on the socket");
      client.getOutputStream();
    }
  }

  /**
   * The backend drops and no fallback is configured, so the session closes itself. The client has
   * to see that: with the socket left open the proxy's worker thread stays parked in a client read
   * for as long as the client keeps the connection, and neither the connection count nor the
   * per-source throttle lease is ever given back.
   */
  private static void aProxyEndedSessionClosesTheClientSocket() throws Exception {
    try (ServerSocket lobby = new ServerSocket(0)) {
      Thread backend = Thread.startVirtualThread(() -> {
        try (Socket socket = lobby.accept()) {
          MinecraftFrames.read(socket.getInputStream(), 4096);
          MinecraftFrames.read(socket.getInputStream(), 4096);
          MinecraftFrames.write(socket.getOutputStream(), legacyLoginSuccess());
          MinecraftFrames.write(socket.getOutputStream(), legacyJoinGame());
        } catch (Exception ignored) { }
      });
      ConduitConfiguration configuration = configuration(
          List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", lobby.getLocalPort()))),
          List.of("lobby"), List.of(), SecuritySettings.defaults());
      try (MinecraftProxy proxy = new MinecraftProxy(configuration)) {
        Thread serving = Thread.startVirtualThread(() -> { try { proxy.serve(); } catch (Exception ignored) { } });
        try (Socket client = new Socket("127.0.0.1", proxy.port())) {
          client.setSoTimeout(15_000);
          MinecraftFrames.write(client.getOutputStream(), new Handshake(47, "localhost", 25565, 2).encode());
          MinecraftFrames.write(client.getOutputStream(), legacyLoginStart());
          require(PlayPackets.packetId(MinecraftFrames.read(client.getInputStream(), 4096)) == 2, "1.8 Login Success");
          require(reachesEndOfStream(client, 15_000), "the proxy must close a client whose session it ended");
        }
        serving.interrupt();
      }
      backend.interrupt();
    }
  }

  /**
   * A client that keeps its connection open but stops reading used to hold its session forever.
   * The backend reader parked in a client write that never completed. The backend then timed the
   * player out and hung up, but nothing woke that write. The client reader sat in a read the
   * client never answered, so the session's threads, its connection slot and its throttle lease
   * were all held until the client went away on its own.
   */
  private static void aClientThatStopsReadingIsCutOff() throws Exception {
    String previous = System.setProperty("conduit.writeDeadlineMillis", "1000");
    try (ServerSocket lobby = new ServerSocket(0)) {
      Thread backend = Thread.startVirtualThread(() -> serveStreamingBackend(lobby, 1_500));
      ConduitConfiguration configuration = configuration(
          List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", lobby.getLocalPort()))),
          List.of("lobby"), List.of(), SecuritySettings.defaults());
      int basePlayers = ConduitMetrics.current().activePlayers();
      int baseBackends = ConduitMetrics.current().activeBackends();
      try (MinecraftProxy proxy = new MinecraftProxy(configuration); Socket client = new Socket()) {
        Thread serving = Thread.startVirtualThread(() -> { try { proxy.serve(); } catch (Exception ignored) { } });
        client.setReceiveBufferSize(1024);
        client.connect(new InetSocketAddress("127.0.0.1", proxy.port()));
        client.setSoTimeout(15_000);
        MinecraftFrames.write(client.getOutputStream(), new Handshake(47, "localhost", 25565, 2).encode());
        MinecraftFrames.write(client.getOutputStream(), legacyLoginStart());
        require(PlayPackets.packetId(MinecraftFrames.read(client.getInputStream(), 4096)) == 2, "1.8 Login Success");
        // From here the client reads nothing, and it does not hang up.
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && !quiet(proxy, basePlayers, baseBackends)) Thread.sleep(50);
        require(proxy.activeConnections() == 0, "a client that stopped reading must lose its session: "
            + proxy.activeConnections() + " connection(s) still held");
        require(ConduitMetrics.current().activePlayers() == basePlayers, "its player slot is released");
        require(ConduitMetrics.current().activeBackends() == baseBackends, "its backend connection is released");
        require(proxy.runtime().security().throttle().inFlight() == 0, "its throttle lease is released");
        serving.interrupt();
      }
      backend.interrupt();
    } finally {
      if (previous == null) System.clearProperty("conduit.writeDeadlineMillis");
      else System.setProperty("conduit.writeDeadlineMillis", previous);
    }
  }

  /** The deadline is for a write that makes no progress, not a slow one: a client reading behind the stream keeps its session. */
  private static void aSlowButReadingClientIsNotCutOff() throws Exception {
    String previous = System.setProperty("conduit.writeDeadlineMillis", "1000");
    try (ServerSocket lobby = new ServerSocket(0)) {
      Thread backend = Thread.startVirtualThread(() -> serveStreamingBackend(lobby, 0));
      ConduitConfiguration configuration = configuration(
          List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", lobby.getLocalPort()))),
          List.of("lobby"), List.of(), SecuritySettings.defaults());
      try (MinecraftProxy proxy = new MinecraftProxy(configuration); Socket client = new Socket()) {
        Thread serving = Thread.startVirtualThread(() -> { try { proxy.serve(); } catch (Exception ignored) { } });
        client.setReceiveBufferSize(1024);
        client.connect(new InetSocketAddress("127.0.0.1", proxy.port()));
        client.setSoTimeout(15_000);
        MinecraftFrames.write(client.getOutputStream(), new Handshake(47, "localhost", 25565, 2).encode());
        MinecraftFrames.write(client.getOutputStream(), legacyLoginStart());
        require(PlayPackets.packetId(MinecraftFrames.read(client.getInputStream(), 4096)) == 2, "1.8 Login Success");
        // Three deadlines' worth of reading well behind the stream: 4 KiB every 100 ms.
        byte[] scratch = new byte[4096];
        long received = 0;
        long end = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < end) {
          int read = client.getInputStream().read(scratch);
          require(read >= 0, "a slow reader must not be cut off (after " + received + " bytes)");
          received += read;
          Thread.sleep(100);
        }
        require(proxy.activeConnections() == 1, "the slow reader still has its session");
        require(received > 16 * 1024, "the stream kept moving: " + received + " bytes");
        serving.interrupt();
      }
      backend.interrupt();
    } finally {
      if (previous == null) System.clearProperty("conduit.writeDeadlineMillis");
      else System.setProperty("conduit.writeDeadlineMillis", previous);
    }
  }

  /**
   * A 1.8 backend that logs a player in and then streams chat as fast as it can be written. With
   * {@code hangUpAfterMillis} above zero it hangs up once that long has passed, the way a real
   * server times out a player that has stopped answering.
   */
  private static void serveStreamingBackend(ServerSocket listener, int hangUpAfterMillis) {
    try (Socket socket = listener.accept()) {
      MinecraftFrames.read(socket.getInputStream(), 4096);
      MinecraftFrames.read(socket.getInputStream(), 4096);
      MinecraftFrames.write(socket.getOutputStream(), legacyLoginSuccess());
      MinecraftFrames.write(socket.getOutputStream(), legacyJoinGame());
      if (hangUpAfterMillis > 0) {
        Thread.startVirtualThread(() -> {
          try { Thread.sleep(hangUpAfterMillis); socket.close(); } catch (Exception ignored) { }
        });
      }
      byte[] chat = legacyServerChat("x".repeat(1000));
      while (true) MinecraftFrames.write(socket.getOutputStream(), chat);
    } catch (Exception ended) { }
  }

  /**
   * On Windows the JDK parks a virtual thread's socket read and socket write through two wepoll
   * handles, and a readiness event can land on the wrong one and be dropped (JDK-8334574). A
   * session reads each socket on one thread and writes it from another, and a real NeoForge join
   * stalled on exactly that: the backend reader parked for good in a write to the client. There,
   * every thread a session blocks on a socket must be a platform thread.
   */
  private static void sessionThreadsStayOffTheWindowsPoller() throws Exception {
    boolean windows = System.getProperty("os.name", "").startsWith("Windows");
    try (ServerSocket lobby = new ServerSocket(0)) {
      Thread backend = Thread.startVirtualThread(() -> {
        try (Socket socket = lobby.accept()) {
          MinecraftFrames.read(socket.getInputStream(), 4096);
          MinecraftFrames.read(socket.getInputStream(), 4096);
          MinecraftFrames.write(socket.getOutputStream(), legacyLoginSuccess());
          MinecraftFrames.write(socket.getOutputStream(), legacyJoinGame());
          socket.getInputStream().read(); // holds the session in Play until the client leaves
        } catch (Exception ignored) { }
      });
      ConduitConfiguration configuration = configuration(
          List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", lobby.getLocalPort()))),
          List.of("lobby"), List.of(), SecuritySettings.defaults());
      try (MinecraftProxy proxy = new MinecraftProxy(configuration)) {
        Thread serving = Thread.startVirtualThread(() -> { try { proxy.serve(); } catch (Exception ignored) { } });
        try (Socket client = new Socket("127.0.0.1", proxy.port())) {
          client.setSoTimeout(15_000);
          MinecraftFrames.write(client.getOutputStream(), new Handshake(47, "localhost", 25565, 2).encode());
          MinecraftFrames.write(client.getOutputStream(), legacyLoginStart());
          require(PlayPackets.packetId(MinecraftFrames.read(client.getInputStream(), 4096)) == 2, "1.8 Login Success");
          MinecraftFrames.read(client.getInputStream(), 4096); // Join Game: both of the session's readers are up
          long platform = Thread.getAllStackTraces().keySet().stream()
              .filter(thread -> thread.getName().startsWith("conduit-io-")).count();
          if (windows) require(platform >= 2, "a Windows session must read and write its sockets on platform threads, found " + platform);
          else require(platform == 0, "elsewhere a session stays on virtual threads, found " + platform);
        }
        serving.interrupt();
      }
      backend.interrupt();
    }
  }

  /**
   * A connection that completes a handshake and then says nothing used to keep its worker thread,
   * its connection slot and its throttle lease for as long as it stayed open: the bot filter's
   * handshake timeout was cleared the moment the handshake arrived, and nothing bounded the reads
   * after it. Both halves of the same stall are checked, login and status.
   */
  private static void aStalledLoginIsTimedOut() throws Exception {
    SecuritySettings security = new SecuritySettings(
        SecuritySettings.ThrottleSettings.defaults(),
        new SecuritySettings.BotFilterSettings(true, 10, 200, 60_000, 60_000),
        SecuritySettings.ChannelGuardSettings.defaults(),
        SecuritySettings.AttackModeSettings.defaults());
    ConduitConfiguration configuration = configuration(
        List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", reservePort()))),
        List.of("lobby"), List.of(), security);
    try (MinecraftProxy proxy = new MinecraftProxy(configuration)) {
      Thread serving = Thread.startVirtualThread(() -> { try { proxy.serve(); } catch (Exception ignored) { } });
      for (int nextState : new int[] {2, 1}) {
        try (Socket client = new Socket("127.0.0.1", proxy.port())) {
          client.setSoTimeout(10_000);
          MinecraftFrames.write(client.getOutputStream(), new Handshake(47, "localhost", 25565, nextState).encode());
          require(reachesEndOfStream(client, 10_000),
              "a connection that stalls after its handshake (next state " + nextState + ") must be dropped");
        }
      }
      serving.interrupt();
    }
  }

  /**
   * Everything either end sends between the handshake and Play is part of a login, and every read
   * of it is bounded. A backend that takes the connection and then goes quiet used to park the
   * joining thread indefinitely, holding the client's socket, its own, the connection slot and the
   * throttle lease; the same deadline covers a client that goes quiet mid-login, which is what a
   * login plugin query relayed to the client depends on.
   */
  private static void aBackendThatNeverFinishesLoginReleasesBothSockets() throws Exception {
    try (ServerSocket lobby = new ServerSocket(0)) {
      java.util.concurrent.CountDownLatch backendClosed = new java.util.concurrent.CountDownLatch(1);
      Thread backend = Thread.startVirtualThread(() -> {
        try (Socket socket = lobby.accept()) {
          MinecraftFrames.read(socket.getInputStream(), 4096);
          MinecraftFrames.read(socket.getInputStream(), 4096);
          // Never answers. The proxy is the only thing that can end this.
          while (socket.getInputStream().read() >= 0) { }
          backendClosed.countDown();
        } catch (Exception closed) { backendClosed.countDown(); }
      });
      ConduitConfiguration configuration = configuration(
          List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", lobby.getLocalPort()))),
          List.of("lobby"), List.of(), SecuritySettings.defaults());
      try (MinecraftProxy proxy = new MinecraftProxy(configuration)) {
        Thread serving = Thread.startVirtualThread(() -> { try { proxy.serve(); } catch (Exception ignored) { } });
        try (Socket client = new Socket("127.0.0.1", proxy.port())) {
          MinecraftFrames.write(client.getOutputStream(), new Handshake(47, "localhost", 25565, 2).encode());
          MinecraftFrames.write(client.getOutputStream(), legacyLoginStart());
          require(reachesEndOfStream(client, 30_000), "a login the backend never finishes must not park the client");
        }
        require(backendClosed.await(30, java.util.concurrent.TimeUnit.SECONDS),
            "the abandoned backend socket must be closed too");
        serving.interrupt();
      }
      backend.interrupt();
    }
  }

  /**
   * A client can send {@code /server} as fast as it can type. Each one used to open its own backend
   * socket and run its own login before finding out another switch had already won the commit, so
   * one player could hold a configured backend open once per packet — and two that both reached the
   * commit left the loser's connection installed and the winner's leaked.
   */
  /**
   * A switch bounds every read of the new backend by its budget, and the commit left that deadline
   * on the socket. A backend that then said nothing for four seconds -- a limbo server sends little
   * more than a keep-alive every few -- read as lost, and the player was sent to the fallback as
   * "unavailable". The initial connection never had this: it clears the deadline when login ends.
   */
  private static void aQuietBackendKeepsTheSwitchedPlayer() throws Exception {
    try (ServerSocket lobby = new ServerSocket(0); ServerSocket limbo = new ServerSocket(0)) {
      Thread lobbyThread = Thread.startVirtualThread(() -> serveLegacyBackend(lobby, 0));
      Thread limboThread = Thread.startVirtualThread(() -> serveLegacyBackend(limbo, 0));
      ConduitConfiguration configuration = configuration(
          List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", lobby.getLocalPort())),
              new BackendServer("limbo", new InetSocketAddress("127.0.0.1", limbo.getLocalPort()))),
          List.of("lobby"), List.of("lobby"), SecuritySettings.defaults());
      try (MinecraftProxy proxy = new MinecraftProxy(configuration)) {
        Thread serving = Thread.startVirtualThread(() -> { try { proxy.serve(); } catch (Exception ignored) { } });
        try (Socket client = new Socket("127.0.0.1", proxy.port())) {
          client.setSoTimeout(15_000);
          MinecraftFrames.write(client.getOutputStream(), new Handshake(47, "localhost", 25565, 2).encode());
          MinecraftFrames.write(client.getOutputStream(), legacyLoginStart());
          require(PlayPackets.packetId(MinecraftFrames.read(client.getInputStream(), 4096)) == 2, "1.8 Login Success");
          MinecraftFrames.write(client.getOutputStream(), legacyChat("/server limbo"));
          long until = System.currentTimeMillis() + 5_000;
          while (!"limbo".equals(backendOf(proxy)) && System.currentTimeMillis() < until) Thread.sleep(50);
          require("limbo".equals(backendOf(proxy)), "the switch reaches limbo, on " + backendOf(proxy));
          Thread.sleep(6_000);
          require("limbo".equals(backendOf(proxy)), "a quiet backend keeps the player, on " + backendOf(proxy));
        }
        serving.interrupt();
      }
      lobbyThread.interrupt();
      limboThread.interrupt();
    }
  }

  /**
   * No first server to be had -- every candidate refused, was unreachable, or had its connection
   * cancelled by a plugin -- and the client's socket was closed with nothing written: the player saw
   * "Connection lost" and no reason. It is still logging in at that point, so it can be told.
   */
  private static void anUnreachableFirstServerIsExplained() throws Exception {
    ConduitConfiguration configuration = configuration(
        List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", reservePort()))),
        List.of("lobby"), List.of(), SecuritySettings.defaults());
    try (MinecraftProxy proxy = new MinecraftProxy(configuration)) {
      Thread serving = Thread.startVirtualThread(() -> { try { proxy.serve(); } catch (Exception ignored) { } });
      try (Socket client = new Socket("127.0.0.1", proxy.port())) {
        client.setSoTimeout(15_000);
        MinecraftFrames.write(client.getOutputStream(), new Handshake(47, "localhost", 25565, 2).encode());
        MinecraftFrames.write(client.getOutputStream(), legacyLoginStart());
        byte[] reply;
        try { reply = MinecraftFrames.read(client.getInputStream(), 4096); }
        catch (java.io.EOFException dropped) { throw new AssertionError("the client was dropped with no reason"); }
        require(PlayPackets.packetId(reply) == 0, "a Login Disconnect, got id " + PlayPackets.packetId(reply));
        require(new String(reply, java.nio.charset.StandardCharsets.UTF_8).contains("try again"), "the reason says what to do");
      }
      serving.interrupt();
    }
  }

  private static String backendOf(MinecraftProxy proxy) {
    List<TrackedPlayer> players = proxy.runtime().playerManager().all();
    return players.isEmpty() ? "(gone)" : players.getFirst().currentBackend();
  }

  private static void rapidServerCommandsOpenOneBackendConnection() throws Exception {
    List<Socket> held = new ArrayList<>();
    AtomicInteger logins = new AtomicInteger();
    try (ServerSocket lobby = new ServerSocket(0); ServerSocket survival = new ServerSocket(0)) {
      Thread lobbyThread = Thread.startVirtualThread(() -> {
        try (Socket socket = lobby.accept()) {
          MinecraftFrames.read(socket.getInputStream(), 4096);
          MinecraftFrames.read(socket.getInputStream(), 4096);
          MinecraftFrames.write(socket.getOutputStream(), legacyLoginSuccess());
          MinecraftFrames.write(socket.getOutputStream(), legacyJoinGame());
          socket.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
        } catch (Exception ignored) { }
      });
      // Accepts and never answers: every login attempt stays in flight for its whole budget.
      Thread survivalThread = Thread.startVirtualThread(() -> {
        try {
          while (true) {
            Socket socket = survival.accept();
            synchronized (held) { held.add(socket); }
            Thread.startVirtualThread(() -> {
              try {
                Handshake handshake = Handshake.decode(MinecraftFrames.read(socket.getInputStream(), 4096));
                if (handshake.nextState() == 2) logins.incrementAndGet();
              } catch (Exception ignored) { }
            });
          }
        } catch (Exception ignored) { }
      });
      ConduitConfiguration configuration = configuration(
          List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", lobby.getLocalPort())),
              new BackendServer("survival", new InetSocketAddress("127.0.0.1", survival.getLocalPort()))),
          List.of("lobby"), List.of("lobby"), SecuritySettings.defaults());
      try (MinecraftProxy proxy = new MinecraftProxy(configuration)) {
        Thread serving = Thread.startVirtualThread(() -> { try { proxy.serve(); } catch (Exception ignored) { } });
        try (Socket client = new Socket("127.0.0.1", proxy.port())) {
          client.setSoTimeout(15_000);
          MinecraftFrames.write(client.getOutputStream(), new Handshake(47, "localhost", 25565, 2).encode());
          MinecraftFrames.write(client.getOutputStream(), legacyLoginStart());
          require(PlayPackets.packetId(MinecraftFrames.read(client.getInputStream(), 4096)) == 2, "1.8 Login Success");
          for (int attempt = 0; attempt < 20; attempt++) {
            MinecraftFrames.write(client.getOutputStream(), legacyChat("/server survival"));
          }
          Thread.sleep(2_000);
          require(logins.get() <= 2, "a burst of /server must not open a backend socket each: " + logins.get());
        }
        serving.interrupt();
      }
      lobbyThread.interrupt();
      survivalThread.interrupt();
    } finally {
      synchronized (held) { for (Socket socket : held) try { socket.close(); } catch (IOException ignored) { } }
    }
  }

  private static ConduitConfiguration configuration(List<BackendServer> backends, List<String> initial,
                                                    List<String> fallback, SecuritySettings security) throws Exception {
    OpsSettings ops = new OpsSettings(OpsSettings.CURRENT_SCHEMA, null,
        new HealthSettings(false, 10_000, 1_500, 3, 2), null, null, security, null, null);
    return new ConduitConfiguration(new InetSocketAddress("127.0.0.1", reservePort()), 4096,
        ForwardingMode.NONE, Optional.empty(), backends, initial, fallback,
        gg.tame.conduit.config.AuthenticationSettings.offline(), Optional.empty(), ops);
  }

  private static boolean reachesEndOfStream(Socket client, int millis) throws Exception {
    client.setSoTimeout(millis);
    InputStream input = client.getInputStream();
    byte[] scratch = new byte[1024];
    try {
      while (input.read(scratch) >= 0) { }
      return true;
    } catch (SocketTimeoutException stalled) {
      return false;
    } catch (IOException closed) {
      return true;
    }
  }

  /** Numbers each login's player: one player may be connected only once, and these crowds are many. */
  private static final AtomicInteger LOGINS = new AtomicInteger();
  private static byte[] legacyLoginStart() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, 0); MinecraftOutput.string(output, "playr" + LOGINS.incrementAndGet());
    }
    return bytes.toByteArray();
  }

  private static byte[] legacyLoginSuccess() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, 2);
      MinecraftOutput.string(output, "00000000-0000-0000-0000-000000000000");
      MinecraftOutput.string(output, "playr");
    }
    return bytes.toByteArray();
  }

  private static byte[] legacyJoinGame() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, 0x01); output.writeInt(1); output.writeByte(0); output.writeByte(0);
      output.writeByte(1); output.writeByte(20); MinecraftOutput.string(output, "flat"); output.writeBoolean(false);
    }
    return bytes.toByteArray();
  }

  private static byte[] legacyChat(String message) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, 0x01); MinecraftOutput.string(output, message);
    }
    return bytes.toByteArray();
  }

  /** 1.8 clientbound Chat (0x02): a JSON text component and a position byte. */
  private static byte[] legacyServerChat(String text) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, 0x02); MinecraftOutput.string(output, "{\"text\":\"" + text + "\"}"); output.writeByte(0);
    }
    return bytes.toByteArray();
  }

  private static int reservePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  /** A session is identified by identity, never by its fields: two of them are the same player. */
  private static final class StubPlayer implements TrackedPlayer {
    private final UUID uniqueId;
    private final String username;
    private StubPlayer(UUID uniqueId, String username) { this.uniqueId = uniqueId; this.username = username; }
    @Override public UUID uniqueId() { return uniqueId; }
    @Override public String username() { return username; }
    @Override public String currentBackend() { return ""; }
    @Override public boolean transferTo(String serverName) { return false; }
  }
}
