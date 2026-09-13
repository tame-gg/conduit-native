package gg.tame.conduit.network;

import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.ProtocolSession;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.StatusResponder;
import gg.tame.conduit.login.LoginPipeline;
import gg.tame.conduit.login.BackendLoginPipeline;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.forwarding.Forwarders;
import gg.tame.conduit.forwarding.PlayerInfoForwarder;
import gg.tame.conduit.routing.BackendSelector;
import java.io.IOException;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executors;

/** Native MVP transport: validates a handshake, then transparently relays one client/backend pair. */
public final class MinecraftProxy implements AutoCloseable {
  private final ServerSocketChannel listener;
  private final ConduitConfiguration configuration;
  private final PlayerInfoForwarder forwarder;
  private volatile boolean running;
  public MinecraftProxy(ConduitConfiguration configuration) throws IOException {
    this.configuration = configuration; this.forwarder = Forwarders.create(configuration); this.listener = ServerSocketChannel.open(); listener.bind(configuration.listener());
  }
  public int port() throws IOException { return ((java.net.InetSocketAddress) listener.getLocalAddress()).getPort(); }
  public void serve() throws IOException {
    running = true;
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      while (running) { SocketChannel client = listener.accept(); workers.submit(() -> handle(client)); }
    }
  }
  private void handle(SocketChannel channel) {
    try (Socket client = channel.socket()) {
      byte[] firstPacket = MinecraftFrames.read(client.getInputStream(), configuration.maxFrameBytes());
      Handshake handshake = Handshake.decode(firstPacket);
      ProtocolSession session = new ProtocolSession(); session.acceptHandshake(handshake.nextState());
      ProtocolDefinition protocol = ProtocolDefinition.forVersion(handshake.protocolVersion());
      if (handshake.nextState() == 1) { serveStatus(client, protocol); return; }
      byte[] loginStart = MinecraftFrames.read(client.getInputStream(), configuration.maxFrameBytes());
      LoginPipeline pipeline = new LoginPipeline(session, protocol); pipeline.observe(PacketDirection.CLIENT_TO_SERVER, loginStart);
      Socket backend = connectBackend();
      if (backend == null) throw new IOException("all configured backends refused the connection");
      try (backend) {
        MinecraftFrames.write(backend.getOutputStream(), firstPacket);
        MinecraftFrames.write(backend.getOutputStream(), loginStart);
        if (forwarder.mode() == gg.tame.conduit.config.ForwardingMode.MODERN) completeBackendLogin(client, backend, protocol, session, pipeline);
        try (var relay = Executors.newVirtualThreadPerTaskExecutor()) {
          relay.submit(() -> copy(client, backend));
          copy(backend, client);
        }
      }
    } catch (IOException exception) { System.err.println("Connection closed: " + exception.getMessage()); }
  }
  private void completeBackendLogin(Socket client, Socket backend, ProtocolDefinition protocol, ProtocolSession session, LoginPipeline clientLogin) throws IOException {
    BackendLoginPipeline backendLogin = new BackendLoginPipeline(protocol, forwarder, clientLogin.player(), client.getInetAddress(), configuration.maxFrameBytes());
    while (backendLogin.state() == gg.tame.conduit.protocol.ConnectionState.LOGIN) {
      byte[] framed = MinecraftFrames.read(backend.getInputStream(), configuration.maxFrameBytes());
      byte[] packet = backendLogin.compression().unwrap(framed);
      byte[] response = backendLogin.onBackendPacket(packet, configuration.maxFrameBytes());
      if (response != null) {
        MinecraftFrames.write(backend.getOutputStream(), backendLogin.compression().wrap(response));
        continue;
      }
      MinecraftFrames.write(client.getOutputStream(), framed);
      clientLogin.observe(PacketDirection.SERVER_TO_CLIENT, packet);
    }
    if (session.state() != gg.tame.conduit.protocol.ConnectionState.CONFIGURATION) session.beginConfiguration();
  }
  private void serveStatus(Socket client, ProtocolDefinition protocol) throws IOException {
    MinecraftFrames.write(client.getOutputStream(), StatusResponder.response(protocol, MinecraftFrames.read(client.getInputStream(), configuration.maxFrameBytes()), "Conduit"));
    MinecraftFrames.write(client.getOutputStream(), StatusResponder.pong(protocol, MinecraftFrames.read(client.getInputStream(), configuration.maxFrameBytes())));
  }
  private Socket connectBackend() {
    for (BackendServer server : new BackendSelector(configuration).candidates()) {
      try { Socket socket = new Socket(); socket.connect(server.address(), 5_000); return socket; }
      catch (IOException ignored) { System.err.println("Backend unavailable: " + server.name()); }
    }
    return null;
  }
  private static void copy(Socket source, Socket destination) {
    try { source.getInputStream().transferTo(destination.getOutputStream()); }
    catch (IOException ignored) { }
    finally { try { destination.shutdownOutput(); } catch (IOException ignored) { } }
  }
  @Override public void close() throws IOException { running = false; listener.close(); }
}
