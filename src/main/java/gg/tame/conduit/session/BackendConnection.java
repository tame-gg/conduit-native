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
  private static final int WRITE_QUEUE_LIMIT = 2048;
  private final BackendServer server;
  private final Socket socket;
  private final BackendLoginPipeline login;
  private final ProtocolDefinition protocol;
  private final int maxFrameBytes;
  private final Object writeLock = new Object();
  private final OutputStream output;
  private int queuedWrites;
  private boolean brandSeen;
  public BackendConnection(BackendServer server, Socket socket, ProtocolDefinition protocol, PlayerInfoForwarder forwarder,
      PlayerProfile player, InetAddress address, ConduitConfiguration configuration, boolean hideLoginSuccess) throws IOException {
    this.server = server; this.socket = socket; this.protocol = protocol; this.maxFrameBytes = configuration.maxFrameBytes();
    this.login = new BackendLoginPipeline(protocol, forwarder, player, address, configuration.maxFrameBytes(), hideLoginSuccess);
    socket.setTcpNoDelay(true);
    this.output = new BufferedOutputStream(socket.getOutputStream(), 8192);
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
    MinecraftFrames.write(socket.getOutputStream(), backendHandshake.encode());
    MinecraftFrames.write(socket.getOutputStream(), LoginStart.encode(player));
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
      if (queuedWrites >= WRITE_QUEUE_LIMIT && !writable()) {
        throw new IOException("backend write backpressure exceeded for " + server.name());
      }
      queuedWrites++;
      MinecraftFrames.writeUnflushed(output, login.compression().wrap(packet));
      queuedWrites--;
      ConduitMetrics.current().outbound(packet.length);
      output.flush();
    }
  }
  public void flush() throws IOException {
    synchronized (writeLock) { output.flush(); }
  }
  public boolean writable() { return !socket.isClosed() && queuedWrites < WRITE_QUEUE_LIMIT / 2; }
  public byte[] rewriteBrand(ConnectionState state, byte[] packet) throws IOException {
    var rewritten = BrandRewriter.rewrite(protocol, state, packet, maxFrameBytes);
    if (rewritten.isPresent()) { brandSeen = true; return rewritten.get(); }
    return packet;
  }
  public void setReadTimeoutMillis(int millis) throws IOException { socket.setSoTimeout(millis); }
  public boolean brandSeen() { return brandSeen; }
  public void markBrandSeen() { brandSeen = true; }
  @Override public void close() {
    try { socket.close(); } catch (IOException ignored) { }
    ConduitMetrics.current().backendClosed();
  }
}
