package gg.tame.conduit.session;

import gg.tame.conduit.brand.BrandRewriter;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.forwarding.PlayerInfoForwarder;
import gg.tame.conduit.login.BackendLoginPipeline;
import gg.tame.conduit.login.LoginStart;
import gg.tame.conduit.login.PlayerProfile;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.PacketCompression;
import gg.tame.conduit.protocol.ProtocolDefinition;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

public final class BackendConnection implements AutoCloseable {
  private final BackendServer server;
  private final Socket socket;
  private final BackendLoginPipeline login;
  private final ProtocolDefinition protocol;
  private final int maxFrameBytes;
  private boolean brandSeen;
  public BackendConnection(BackendServer server, Socket socket, ProtocolDefinition protocol, PlayerInfoForwarder forwarder,
      PlayerProfile player, InetAddress address, ConduitConfiguration configuration, boolean hideLoginSuccess) {
    this.server = server; this.socket = socket; this.protocol = protocol; this.maxFrameBytes = configuration.maxFrameBytes();
    this.login = new BackendLoginPipeline(protocol, forwarder, player, address, configuration.maxFrameBytes(), hideLoginSuccess);
  }
  public static Socket open(BackendServer server) throws IOException {
    Socket socket = new Socket();
    socket.connect(server.address(), 5_000);
    return socket;
  }
  public static void handshake(Socket socket, Handshake clientHandshake, BackendServer server, PlayerProfile player) throws IOException {
    Handshake backendHandshake = new Handshake(clientHandshake.protocolVersion(), server.address().getHostString(), server.address().getPort(), 2);
    MinecraftFrames.write(socket.getOutputStream(), backendHandshake.encode());
    MinecraftFrames.write(socket.getOutputStream(), LoginStart.encode(player));
  }
  public BackendServer server() { return server; }
  public ConnectionState state() { return login.state(); }
  public PacketCompression compression() { return login.compression(); }
  public BackendLoginPipeline login() { return login; }
  public byte[] readUncompressed() throws IOException {
    return login.compression().unwrap(MinecraftFrames.read(socket.getInputStream(), maxFrameBytes));
  }
  public void writeUncompressed(byte[] packet) throws IOException {
    MinecraftFrames.write(socket.getOutputStream(), login.compression().wrap(packet));
  }
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
  }
}
