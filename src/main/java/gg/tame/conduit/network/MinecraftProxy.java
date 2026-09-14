package gg.tame.conduit.network;

import gg.tame.conduit.api.event.player.PlayerAuthenticatedEvent;
import gg.tame.conduit.api.event.player.PlayerLoginEvent;
import gg.tame.conduit.api.event.proxy.ProxyShutdownEvent;
import gg.tame.conduit.api.event.proxy.ProxyStartEvent;
import gg.tame.conduit.auth.Authenticators;
import gg.tame.conduit.auth.AuthenticationException;
import gg.tame.conduit.auth.PlayerAuthenticator;
import gg.tame.conduit.auth.SessionQuery;
import gg.tame.conduit.command.CoreCommands;
import gg.tame.conduit.config.AuthenticationMode;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.crypto.RsaKeys;
import gg.tame.conduit.forwarding.Forwarders;
import gg.tame.conduit.forwarding.PlayerInfoForwarder;
import gg.tame.conduit.log.ConduitLog;
import gg.tame.conduit.login.EncryptionHandshake;
import gg.tame.conduit.login.LoginDisconnect;
import gg.tame.conduit.login.LoginPipeline;
import gg.tame.conduit.metrics.ConduitMetrics;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.ProtocolSession;
import gg.tame.conduit.protocol.StatusResponder;
import gg.tame.conduit.runtime.ConduitRuntime;
import gg.tame.conduit.session.PlayerSession;
import java.io.IOException;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/** Native transport: handshake, optional online-mode encryption, then a persistent player session. */
public final class MinecraftProxy implements AutoCloseable {
  private static final int MAX_CONNECTIONS = 2048;
  private static final int MAX_CONCURRENT_AUTH = 32;
  private final ServerSocketChannel listener;
  private final ConduitConfiguration configuration;
  private final PlayerInfoForwarder forwarder;
  private final PlayerAuthenticator authenticator;
  private final KeyPair rsaKeys;
  private final ConduitRuntime runtime;
  private final Semaphore authPermits = new Semaphore(MAX_CONCURRENT_AUTH);
  private final AtomicInteger connections = new AtomicInteger();
  private volatile boolean running;
  private volatile boolean accepting = true;

  public MinecraftProxy(ConduitConfiguration configuration) throws IOException {
    this(configuration, Authenticators.create(configuration.authentication()), RsaKeys.generate(), Path.of("plugins"));
  }
  public MinecraftProxy(ConduitConfiguration configuration, PlayerAuthenticator authenticator, KeyPair rsaKeys) throws IOException {
    this(configuration, authenticator, rsaKeys, Path.of("plugins"));
  }
  public MinecraftProxy(ConduitConfiguration configuration, PlayerAuthenticator authenticator, KeyPair rsaKeys, Path pluginsDirectory) throws IOException {
    this.configuration = configuration; this.authenticator = authenticator; this.rsaKeys = rsaKeys;
    this.forwarder = Forwarders.create(configuration); this.listener = ServerSocketChannel.open(); listener.bind(configuration.listener());
    Path configDir = pluginsDirectory.getParent() == null ? Path.of(".") : pluginsDirectory.getParent();
    this.runtime = new ConduitRuntime(configuration, pluginsDirectory, configDir);
    CoreCommands.register(runtime);
    try {
      Class.forName("gg.tame.conduit.compat.velocity.VelocityBoot")
          .getMethod("install", ConduitRuntime.class)
          .invoke(null, runtime);
    } catch (ClassNotFoundException ignored) {
    } catch (ReflectiveOperationException exception) {
      ConduitLog.error("Velocity compatibility layer failed to install", exception);
    }
  }
  public ConduitRuntime runtime() { return runtime; }
  public void probeBackends() { runtime.selector().probeAll(); }
  public int port() throws IOException { return ((java.net.InetSocketAddress) listener.getLocalAddress()).getPort(); }
  public void serve() throws IOException {
    running = true;
    accepting = true;
    try { runtime.pluginRuntime().loadAll(); } catch (Exception exception) { ConduitLog.error("plugin load failed", exception); }
    runtime.events().fire(new ProxyStartEvent(runtime));
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      while (running) {
        SocketChannel client = listener.accept();
        if (!accepting || runtime.gracefulShutdown().isShuttingDown()) {
          try { client.close(); } catch (IOException ignored) { }
          continue;
        }
        if (connections.incrementAndGet() > MAX_CONNECTIONS) {
          connections.decrementAndGet();
          try { client.close(); } catch (IOException ignored) { }
          ConduitLog.warn("rejected connection: at max connections");
          continue;
        }
        workers.submit(() -> handle(client));
      }
    } finally {
      runtime.events().fire(new ProxyShutdownEvent(runtime));
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
      if (handshake.nextState() == 1) { serveStatus(transport, protocol, handshake.protocolVersion()); return; }
      if (!runtime.versionGate().allows(handshake.protocolVersion())) {
        try { transport.write(LoginDisconnect.encode(protocol, runtime.versionGate().kickMessage())); } catch (IOException ignored) { }
        return;
      }
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
      try (PlayerSession player = new PlayerSession(configuration, transport, protocol, session, pipeline, forwarder, runtime,
          handshake, firstPacket, loginStart, configuration.forwardedPlayerAddress().orElse(client.getInetAddress()))) {
        if (runtime.maintenance().isActive() && !maintenanceBypass(player)) {
          try { transport.write(LoginDisconnect.encode(protocol, runtime.maintenance().kickMessage())); } catch (IOException ignored) { }
          return;
        }
        runtime.events().fire(new PlayerLoginEvent(player));
        if (player.authenticated()) runtime.events().fire(new PlayerAuthenticatedEvent(player));
        player.play();
      }
    } catch (IOException exception) { ConduitLog.warn("Connection closed: " + exception.getMessage()); }
    finally { connections.decrementAndGet(); }
  }
  private boolean maintenanceBypass(PlayerSession player) {
    if (runtime.maintenance().settings().allowsUsername(player.username())) return true;
    // Built-in permissive provider would otherwise make maintenance meaningless.
    if (runtime.permissions() instanceof gg.tame.conduit.permission.PermissivePermissionProvider) return false;
    return player.hasPermission(gg.tame.conduit.command.Permissions.MAINTENANCE_BYPASS)
        || player.hasPermission(gg.tame.conduit.command.Permissions.CONDUIT_ADMIN);
  }
  private void authenticateOnline(PacketTransport transport, ProtocolDefinition protocol, LoginPipeline pipeline, String address) throws IOException, AuthenticationException {
    if (!authPermits.tryAcquire()) throw new AuthenticationException("authentication busy");
    try {
      EncryptionHandshake handshake = new EncryptionHandshake(rsaKeys);
      transport.beginNegotiation();
      transport.write(handshake.request(protocol).encode(protocol));
      ConduitLog.info("Encryption request sent.");
      byte[] response;
      try { response = transport.read(configuration.maxFrameBytes()); }
      catch (IOException exception) { throw new AuthenticationException("missing encryption response", exception); }
      byte[] secret;
      try { secret = handshake.sharedSecret(protocol, response); }
      catch (AuthenticationException exception) { throw exception; }
      catch (Exception exception) { throw new AuthenticationException("invalid encryption response", exception); }
      transport.enableEncryption(secret);
      ConduitLog.info("Client encryption enabled.");
      String hash = handshake.serverHash(secret);
      var authenticated = authenticator.verify(new SessionQuery(pipeline.player().username(), hash, Optional.of(address)));
      pipeline.adopt(authenticated);
      ConduitMetrics.current().authentication();
      ConduitLog.info("Session verified for " + authenticated.username() + " (" + authenticated.uniqueId() + ").");
      ConduitLog.info(authenticated.summary());
    } finally { authPermits.release(); }
  }
  private void serveStatus(PacketTransport client, ProtocolDefinition protocol, int clientProtocol) throws IOException {
    String description = "Conduit";
    String versionName = "Conduit " + protocol.version().displayName();
    int advertised = protocol.version().number();
    if (runtime.maintenance().isActive()) {
      description = runtime.maintenance().motd();
    }
    if (runtime.versionGate().isEnabled() && !runtime.versionGate().allows(clientProtocol)) {
      versionName = runtime.versionGate().pingVersionName(clientProtocol);
      advertised = runtime.versionGate().statusProtocolAdvertisement(clientProtocol).orElse(clientProtocol);
      if (!runtime.maintenance().isActive()) description = runtime.versionGate().kickMessage();
    }
    client.write(StatusResponder.response(protocol, client.read(configuration.maxFrameBytes()), description, versionName, advertised));
    client.write(StatusResponder.pong(protocol, client.read(configuration.maxFrameBytes())));
  }
  @Override public void close() throws IOException {
    if (running) {
      runtime.shutdownGracefully(() -> accepting = false);
    }
    running = false;
    accepting = false;
    listener.close();
    runtime.close();
  }
}
