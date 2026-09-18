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
  private final OutputStream output;
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
    Socket socket = new Socket();
    socket.connect(server.address(), 3_000);
    ConduitMetrics.current().backendConnect(System.nanoTime() - start);
    return socket;
  }
  public static void handshake(Socket socket, Handshake clientHandshake, BackendServer server, PlayerProfile player) throws IOException {
    handshake(socket, clientHandshake, server, player, FmlAddressMarkers.MarkerKind.NONE, ModLoaderFamily.UNKNOWN);
  }

  public static void handshake(Socket socket, Handshake clientHandshake, BackendServer server, PlayerProfile player,
                               FmlAddressMarkers.MarkerKind clientMarker, ModLoaderFamily clientFamily) throws IOException {
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
  public int available() throws IOException { return socket.getInputStream().available(); }
  public byte[] readUncompressed() throws IOException {
    byte[] packet = login.compression().unwrap(MinecraftFrames.read(socket.getInputStream(), maxFrameBytes));
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
  public void setReadTimeoutMillis(int millis) throws IOException { socket.setSoTimeout(millis); }
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
    try { socket.close(); } catch (IOException ignored) { }
    ConduitMetrics.current().backendClosed();
  }
}
