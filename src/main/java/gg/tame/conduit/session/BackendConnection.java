// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.session;

import gg.tame.conduit.brand.BrandRewriter;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.forwarding.PlayerInfoForwarder;
import gg.tame.conduit.login.BackendLoginPipeline;
import gg.tame.conduit.login.LoginStart;
import gg.tame.conduit.login.PlayerProfile;
import gg.tame.conduit.metrics.ConduitMetrics;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.PacketCompression;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.modded.FmlAddressMarkers;
import gg.tame.conduit.modded.ModLoaderFamily;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;

public final class BackendConnection implements AutoCloseable {
  private final BackendServer server;
  private final Socket socket;
  private final BackendLoginPipeline login;
  private final ProtocolDefinition protocol;
  private final int maxFrameBytes;
  private final Object writeLock = new Object();
  private OutputStream output;
  private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
  private boolean brandSeen;
  public BackendConnection(BackendServer server, Socket socket, ProtocolDefinition protocol, PlayerInfoForwarder forwarder,
      PlayerProfile player, InetAddress address, ConduitConfiguration configuration, boolean hideLoginSuccess) throws IOException {
    this.server = server; this.socket = socket; this.protocol = protocol; this.maxFrameBytes = configuration.maxFrameBytes();
    this.login = new BackendLoginPipeline(protocol, forwarder, player, address, configuration.maxFrameBytes(), hideLoginSuccess);
    socket.setTcpNoDelay(true);
    this.output = new BufferedOutputStream(gg.tame.conduit.network.DeadlineOutputStream.of(socket), 8192);
    ConduitMetrics.current().backendOpened();
  }
  public static Socket open(BackendServer server) throws IOException {
    long start = System.nanoTime();
    // Through a channel so the connection can later be watched by the ConnectionSelector: a plain
    // Socket has none, and a session whose backend cannot be selected keeps a thread for it.
    Socket socket = java.nio.channels.SocketChannel.open().socket();
    try { socket.connect(server.address(), 3_000); }
    catch (IOException unreachable) {
      ConduitMetrics.current().backendConnectFailed();
      socket.close();
      throw unreachable;
    }
    ConduitMetrics.current().backendConnect(System.nanoTime() - start);
    return socket;
  }
  public static void handshake(Socket socket, Handshake clientHandshake, BackendServer server, PlayerProfile player) throws IOException {
    handshake(socket, clientHandshake, server, player, FmlAddressMarkers.MarkerKind.NONE, ModLoaderFamily.UNKNOWN);
  }

  public static void handshake(Socket socket, Handshake clientHandshake, BackendServer server, PlayerProfile player,
                               FmlAddressMarkers.MarkerKind clientMarker, ModLoaderFamily clientFamily) throws IOException {
    handshake(socket, clientHandshake, server, player, clientMarker, clientFamily, new gg.tame.conduit.forwarding.NoneForwarder(), null);
  }

  public static void handshake(Socket socket, Handshake clientHandshake, BackendServer server, PlayerProfile player,
                               FmlAddressMarkers.MarkerKind clientMarker, ModLoaderFamily clientFamily,
                               PlayerInfoForwarder forwarder, InetAddress client) throws IOException {
    String host = server.address().getHostString();
    boolean wantsForge = server.accepts(ModLoaderFamily.FORGE) && !server.supportedModLoaders().isEmpty()
        && server.supportedModLoaders().contains(ModLoaderFamily.FORGE)
        && !server.supportedModLoaders().contains(ModLoaderFamily.VANILLA);
    boolean wantsNeo = server.accepts(ModLoaderFamily.NEOFORGE) && !server.supportedModLoaders().isEmpty()
        && server.supportedModLoaders().contains(ModLoaderFamily.NEOFORGE)
        && !server.supportedModLoaders().contains(ModLoaderFamily.VANILLA);
    // When backend is unrestricted, preserve observed client marker so Forge/NeoForge backends keep working.
    FmlAddressMarkers.MarkerKind marker = clientMarker;
    if (marker == FmlAddressMarkers.MarkerKind.NONE) {
      if (wantsNeo) marker = FmlAddressMarkers.MarkerKind.FML3;
      else if (wantsForge) marker = FmlAddressMarkers.MarkerKind.FML2;
    }
    if (marker != FmlAddressMarkers.MarkerKind.NONE) {
      host = FmlAddressMarkers.append(host, marker);
    }
    host = forwarder.handshakeHost(host, player, client);
    Handshake backendHandshake = new Handshake(clientHandshake.protocolVersion(), host, server.address().getPort(), 2);
    byte[] handshakeBytes = backendHandshake.encode();
    MinecraftFrames.write(socket.getOutputStream(), handshakeBytes);
    ProtocolDefinition loginProtocol = ProtocolDefinition.hasCodec(clientHandshake.protocolVersion())
        ? ProtocolDefinition.forVersion(clientHandshake.protocolVersion())
        : null;
    byte[] loginStart = LoginStart.encode(player, loginProtocol);
    if (gg.tame.conduit.protocol.ProtocolTrace.enabled()) {
      gg.tame.conduit.protocol.ProtocolTrace.note("backend-open " + server.name() + ":"
          + server.address().getPort() + " handshakeProtocol=" + clientHandshake.protocolVersion()
          + " loginProtocol=" + (loginProtocol == null ? "none" : loginProtocol.version().number()));
    }
    MinecraftFrames.write(socket.getOutputStream(), loginStart);
  }
  public BackendServer server() { return server; }
  public ConnectionState state() { return login.state(); }
  public PacketCompression compression() { return login.compression(); }
  public BackendLoginPipeline login() { return login; }
  public int available() throws IOException { return input().available(); }
  public byte[] readUncompressed() throws IOException {
    byte[] packet = login.compression().unwrap(MinecraftFrames.read(input(), maxFrameBytes));
    ConduitMetrics.current().inbound(packet.length);
    return packet;
  }
  public void writeUncompressed(byte[] packet) throws IOException {
    synchronized (writeLock) {
      if (gg.tame.conduit.protocol.ProtocolTrace.bodies()) {
        // Which backend a packet went to, and in what state, is the difference between a correct
        // switch and one that writes the client's dialect at the wrong server.
        gg.tame.conduit.protocol.ProtocolTrace.note("backend-write " + server.name() + ":"
            + server.address().getPort() + " state=" + login.state()
            + " len=" + packet.length + " " + gg.tame.conduit.protocol.ProtocolTrace.hex(packet, 24));
      }
      MinecraftFrames.writeUnflushed(output, login.compression().wrap(packet));
      ConduitMetrics.current().outbound(packet.length);
      output.flush();
    }
  }
  public void flush() throws IOException {
    synchronized (writeLock) { output.flush(); }
  }
  public byte[] rewriteBrand(ConnectionState state, byte[] packet) throws IOException {
    var rewritten = BrandRewriter.rewrite(protocol, state, packet, maxFrameBytes);
    if (rewritten.isPresent()) { brandSeen = true; return rewritten.get(); }
    return packet;
  }
  // A backend on the selector is non-blocking and has no read to bound: it is read only when a
  // whole frame is already buffered, and never waited on.
  public void setReadTimeoutMillis(int millis) throws IOException { if (attached == null) socket.setSoTimeout(millis); }
  public boolean brandSeen() { return brandSeen; }
  public void markBrandSeen() { brandSeen = true; }
  /**
   * Idempotent. A lost backend is closed where it is lost and again when the session closes, and a
   * switch closes the one it replaced; counting each of those as a separate close walked the live
   * backend gauge down past the connections that were still open, and it reported none while other
   * players were still on theirs.
   */
  @Override public void close() {
    if (!closed.compareAndSet(false, true)) return;
    gg.tame.conduit.network.ConnectionSelector.Registration registration = attached;
    if (registration != null) registration.cancel();
    try { socket.close(); } catch (IOException ignored) { }
    ConduitMetrics.current().backendClosed();
  }

  // --- watching this backend instead of parking a thread on it ------------------------------------

  private volatile gg.tame.conduit.network.ConnectionSelector.Registration attached;
  private java.io.InputStream blocking;

  /** Whether there is a channel behind the socket, which a connection has to have to be watched. */
  public boolean selectable() { return socket.getChannel() != null; }

  /**
   * Moves this backend onto the selector, carrying whatever the blocking stream had already taken
   * off the socket. Null when there is no channel to watch, and the caller then keeps its thread.
   */
  public gg.tame.conduit.network.ConnectionSelector.Registration attachTo(
      gg.tame.conduit.network.ConnectionSelector selector,
      gg.tame.conduit.network.ConnectionSelector.Handler handler) throws IOException {
    java.nio.channels.SocketChannel channel = socket.getChannel();
    if (channel == null) return null;
    // While the channel is still blocking: registering makes it non-blocking, and a flush through
    // the socket's own output stream after that throws IllegalBlockingModeException.
    java.io.InputStream current = input();
    int buffered = current.available();
    byte[] carried = buffered > 0 ? current.readNBytes(buffered) : new byte[0];
    synchronized (writeLock) {
      output.flush();
      gg.tame.conduit.network.ConnectionSelector.Registration registration =
          selector.register(channel, carried, handler);
      this.output = registration.output();
      this.attached = registration;
      return registration;
    }
  }

  /** Fills until a whole frame is buffered; false when the socket has no more to give just now. */
  public boolean nextFrameReady() throws IOException {
    gg.tame.conduit.network.ConnectionSelector.Registration registration = attached;
    return registration == null || registration.nextFrameReady(maxFrameBytes);
  }

  /** Whether the client this backend's packets are written to is too far behind to be given more. */
  public boolean sinkCongested() {
    gg.tame.conduit.network.ConnectionSelector.Registration registration = attached;
    return registration != null && registration.sinkCongested();
  }

  /** Whether the backend has hung up and nothing more can be relayed from what it sent. */
  public boolean ended() {
    gg.tame.conduit.network.ConnectionSelector.Registration registration = attached;
    return registration != null && registration.ended(maxFrameBytes);
  }

  /** Takes whatever the socket has now: bytes added, 0 for none, -1 once the backend has hung up. */
  public int fill() throws IOException {
    gg.tame.conduit.network.ConnectionSelector.Registration registration = attached;
    return registration == null ? 0 : registration.fill();
  }

  private java.io.InputStream input() throws IOException {
    gg.tame.conduit.network.ConnectionSelector.Registration registration = attached;
    if (registration != null) return registration.input();
    if (blocking == null) blocking = socket.getInputStream();
    return blocking;
  }
}
