package gg.tame.conduit.network;

import gg.tame.conduit.auth.Authenticators;
import gg.tame.conduit.auth.AuthenticationException;
import gg.tame.conduit.auth.PlayerAuthenticator;
import gg.tame.conduit.auth.SessionQuery;
import gg.tame.conduit.config.AuthenticationMode;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.crypto.RsaKeys;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.ProtocolSession;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.StatusResponder;
import gg.tame.conduit.login.EncryptionHandshake;
import gg.tame.conduit.login.LoginDisconnect;
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
import java.security.KeyPair;
import java.util.Optional;
import java.util.concurrent.Executors;

/** Native transport: handshake, optional online-mode encryption, then backend relay. */
public final class MinecraftProxy implements AutoCloseable {
  private final ServerSocketChannel listener;
  private final ConduitConfiguration configuration;
  private final PlayerInfoForwarder forwarder;
  private final PlayerAuthenticator authenticator;
  private final KeyPair rsaKeys;
  private volatile boolean running;
  public MinecraftProxy(ConduitConfiguration configuration) throws IOException {
    this(configuration, Authenticators.create(configuration.authentication()), RsaKeys.generate());
  }
  public MinecraftProxy(ConduitConfiguration configuration, PlayerAuthenticator authenticator, KeyPair rsaKeys) throws IOException {
    this.configuration = configuration; this.authenticator = authenticator; this.rsaKeys = rsaKeys;
    this.forwarder = Forwarders.create(configuration); this.listener = ServerSocketChannel.open(); listener.bind(configuration.listener());
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
      PacketTransport transport = new PacketTransport(client);
      byte[] firstPacket = transport.read(configuration.maxFrameBytes());
      Handshake handshake = Handshake.decode(firstPacket);
      ProtocolSession session = new ProtocolSession(); session.acceptHandshake(handshake.nextState());
      ProtocolDefinition protocol = ProtocolDefinition.forVersion(handshake.protocolVersion());
      if (handshake.nextState() == 1) { serveStatus(transport, protocol); return; }
      byte[] loginStart = transport.read(configuration.maxFrameBytes());
      LoginPipeline pipeline = new LoginPipeline(session, protocol); pipeline.observe(PacketDirection.CLIENT_TO_SERVER, loginStart);
      if (authenticator.mode() == AuthenticationMode.ONLINE) {
        try { authenticateOnline(transport, protocol, pipeline, client.getInetAddress().getHostAddress()); }
        catch (AuthenticationException exception) {
          try { transport.write(LoginDisconnect.encode(protocol, "Failed to verify username!")); } catch (IOException ignored) { }
          throw new IOException(exception.getMessage(), exception);
        }
      }
      Socket backend = connectBackend();
      if (backend == null) throw new IOException("all configured backends refused the connection");
      try (backend) {
        MinecraftFrames.write(backend.getOutputStream(), firstPacket);
        MinecraftFrames.write(backend.getOutputStream(), loginStart);
        if (forwarder.mode() == gg.tame.conduit.config.ForwardingMode.MODERN) completeBackendLogin(transport, backend, protocol, session, pipeline, client.getInetAddress());
        try (var relay = Executors.newVirtualThreadPerTaskExecutor()) {
          relay.submit(() -> { try { copy(transport.input(), backend.getOutputStream()); } catch (IOException ignored) { } });
          copy(backend.getInputStream(), transport.output());
        }
      }
    } catch (IOException exception) { System.err.println("Connection closed: " + exception.getMessage()); }
  }
  private void authenticateOnline(PacketTransport transport, ProtocolDefinition protocol, LoginPipeline pipeline, String address) throws IOException, AuthenticationException {
    EncryptionHandshake handshake = new EncryptionHandshake(rsaKeys);
    transport.beginNegotiation();
    transport.write(handshake.request().encode(protocol));
    System.out.println("Encryption request sent.");
    byte[] response;
    try { response = transport.read(configuration.maxFrameBytes()); }
    catch (IOException exception) { throw new AuthenticationException("missing encryption response", exception); }
    byte[] secret;
    try { secret = handshake.sharedSecret(protocol, response); }
    catch (AuthenticationException exception) { throw exception; }
    catch (Exception exception) { throw new AuthenticationException("invalid encryption response", exception); }
    transport.enableEncryption(secret);
    System.out.println("Client encryption enabled.");
    String hash = handshake.serverHash(secret);
    var authenticated = authenticator.verify(new SessionQuery(pipeline.player().username(), hash, Optional.of(address)));
    pipeline.adopt(authenticated);
    System.out.println("Session verified for " + authenticated.username() + " (" + authenticated.uniqueId() + ").");
  }
  private void completeBackendLogin(PacketTransport client, Socket backend, ProtocolDefinition protocol, ProtocolSession session, LoginPipeline clientLogin, java.net.InetAddress address) throws IOException {
    BackendLoginPipeline backendLogin = new BackendLoginPipeline(protocol, forwarder, clientLogin.player(), address, configuration.maxFrameBytes());
    while (backendLogin.state() == gg.tame.conduit.protocol.ConnectionState.LOGIN) {
      byte[] framed = MinecraftFrames.read(backend.getInputStream(), configuration.maxFrameBytes());
      byte[] packet = backendLogin.compression().unwrap(framed);
      byte[] response = backendLogin.onBackendPacket(packet, configuration.maxFrameBytes());
      if (response != null) {
        MinecraftFrames.write(backend.getOutputStream(), backendLogin.compression().wrap(response));
        continue;
      }
      client.write(framed);
      clientLogin.observe(PacketDirection.SERVER_TO_CLIENT, packet);
    }
    if (session.state() != gg.tame.conduit.protocol.ConnectionState.CONFIGURATION) session.beginConfiguration();
  }
  private void serveStatus(PacketTransport client, ProtocolDefinition protocol) throws IOException {
    client.write(StatusResponder.response(protocol, client.read(configuration.maxFrameBytes()), "Conduit"));
    client.write(StatusResponder.pong(protocol, client.read(configuration.maxFrameBytes())));
  }
  private Socket connectBackend() {
    for (BackendServer server : new BackendSelector(configuration).candidates()) {
      try { Socket socket = new Socket(); socket.connect(server.address(), 5_000); return socket; }
      catch (IOException ignored) { System.err.println("Backend unavailable: " + server.name()); }
    }
    return null;
  }
  private static void copy(java.io.InputStream source, java.io.OutputStream destination) {
    try { source.transferTo(destination); destination.flush(); }
    catch (IOException ignored) { }
    finally { try { destination.flush(); } catch (IOException ignored) { } }
  }
  @Override public void close() throws IOException { running = false; listener.close(); }
}
