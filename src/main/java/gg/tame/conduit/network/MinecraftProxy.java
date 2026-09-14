package gg.tame.conduit.network;

import gg.tame.conduit.auth.Authenticators;
import gg.tame.conduit.auth.AuthenticationException;
import gg.tame.conduit.auth.PlayerAuthenticator;
import gg.tame.conduit.auth.SessionQuery;
import gg.tame.conduit.command.CommandManager;
import gg.tame.conduit.command.CoreCommands;
import gg.tame.conduit.config.AuthenticationMode;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.crypto.RsaKeys;
import gg.tame.conduit.forwarding.Forwarders;
import gg.tame.conduit.forwarding.PlayerInfoForwarder;
import gg.tame.conduit.login.EncryptionHandshake;
import gg.tame.conduit.login.LoginDisconnect;
import gg.tame.conduit.login.LoginPipeline;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.ProtocolSession;
import gg.tame.conduit.protocol.StatusResponder;
import gg.tame.conduit.routing.BackendSelector;
import gg.tame.conduit.session.PlayerManager;
import gg.tame.conduit.session.PlayerSession;
import java.io.IOException;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.security.KeyPair;
import java.util.Optional;
import java.util.concurrent.Executors;

/** Native transport: handshake, optional online-mode encryption, then a persistent player session. */
public final class MinecraftProxy implements AutoCloseable {
  private final ServerSocketChannel listener;
  private final ConduitConfiguration configuration;
  private final PlayerInfoForwarder forwarder;
  private final PlayerAuthenticator authenticator;
  private final KeyPair rsaKeys;
  private final CommandManager commands;
  private final PlayerManager players;
  private final BackendSelector selector;
  private volatile boolean running;
  public MinecraftProxy(ConduitConfiguration configuration) throws IOException {
    this(configuration, Authenticators.create(configuration.authentication()), RsaKeys.generate());
  }
  public MinecraftProxy(ConduitConfiguration configuration, PlayerAuthenticator authenticator, KeyPair rsaKeys) throws IOException {
    this.configuration = configuration; this.authenticator = authenticator; this.rsaKeys = rsaKeys;
    this.forwarder = Forwarders.create(configuration); this.listener = ServerSocketChannel.open(); listener.bind(configuration.listener());
    this.selector = new BackendSelector(configuration);
    this.commands = new CommandManager();
    this.players = new PlayerManager();
    CoreCommands.register(commands, selector.registry(), players);
  }
  public void probeBackends() { selector.probeAll(); }
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
      ProtocolDefinition protocol;
      try { protocol = ProtocolDefinition.forVersion(handshake.protocolVersion()); }
      catch (IllegalArgumentException unsupported) {
        if (handshake.nextState() == 2) {
          try { transport.write(LoginDisconnect.encode(ProtocolDefinition.forVersion(765), "Unsupported Minecraft version.")); } catch (IOException ignored) { }
        }
        throw new IOException(unsupported.getMessage(), unsupported);
      }
      if (handshake.nextState() == 1) { serveStatus(transport, protocol); return; }
      byte[] loginStart = transport.read(configuration.maxFrameBytes());
      LoginPipeline pipeline = new LoginPipeline(session, protocol); pipeline.observe(gg.tame.conduit.protocol.PacketDirection.CLIENT_TO_SERVER, loginStart);
      if (authenticator.mode() == AuthenticationMode.ONLINE) {
        try {
          authenticateOnline(transport, protocol, pipeline, client.getInetAddress().getHostAddress());
        } catch (AuthenticationException exception) {
          try { transport.write(LoginDisconnect.encode(protocol, "Failed to verify username!")); } catch (IOException ignored) { }
          throw new IOException(exception.getMessage(), exception);
        }
      }
      try (PlayerSession player = new PlayerSession(configuration, transport, protocol, session, pipeline, forwarder, commands, players, selector,
          handshake, firstPacket, loginStart, client.getInetAddress())) {
        player.play();
      }
    } catch (IOException exception) { System.err.println("Connection closed: " + exception.getMessage()); }
  }
  private void authenticateOnline(PacketTransport transport, ProtocolDefinition protocol, LoginPipeline pipeline, String address) throws IOException, AuthenticationException {
    EncryptionHandshake handshake = new EncryptionHandshake(rsaKeys);
    transport.beginNegotiation();
    transport.write(handshake.request(protocol).encode(protocol));
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
  private void serveStatus(PacketTransport client, ProtocolDefinition protocol) throws IOException {
    client.write(StatusResponder.response(protocol, client.read(configuration.maxFrameBytes()), "Conduit"));
    client.write(StatusResponder.pong(protocol, client.read(configuration.maxFrameBytes())));
  }
  @Override public void close() throws IOException { running = false; listener.close(); }
}
