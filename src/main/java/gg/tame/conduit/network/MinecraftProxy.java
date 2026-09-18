package gg.tame.conduit.network;

import gg.tame.conduit.api.event.player.PlayerAuthenticatedEvent;
import gg.tame.conduit.api.event.player.PlayerLoginEvent;
import gg.tame.conduit.api.event.proxy.ServerListPingEvent;
import gg.tame.conduit.api.text.Text;
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
import gg.tame.conduit.security.ConnectionThrottle;
import gg.tame.conduit.session.PlayerSession;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/** Native transport: handshake, optional online-mode encryption, then a persistent player session. */
public final class MinecraftProxy implements AutoCloseable {
  private static final int MAX_CONNECTIONS = 2048;
  private static final int MAX_CONCURRENT_AUTH = 32;
  private static final int ENCRYPTION_RESPONSE_TIMEOUT_MS = 30_000;
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
    this(configuration, authenticator, rsaKeys, pluginsDirectory, ServerSocketChannel.open());
  }
  /** Serves on a listener the caller supplies; the proxy binds and owns it from here. */
  public MinecraftProxy(ConduitConfiguration configuration, PlayerAuthenticator authenticator, KeyPair rsaKeys,
                        Path pluginsDirectory, ServerSocketChannel listener) throws IOException {
    this.configuration = configuration; this.authenticator = authenticator; this.rsaKeys = rsaKeys;
    this.forwarder = Forwarders.create(configuration); this.listener = listener; listener.bind(configuration.listener());
    Path configDir = pluginsDirectory.getParent() == null ? Path.of(".") : pluginsDirectory.getParent();
    this.runtime = new ConduitRuntime(configuration, pluginsDirectory, configDir);
    runtime.bindListener((java.net.InetSocketAddress) listener.getLocalAddress());
    runtime.onShutdownRequest(() -> {
      try { close(); } catch (IOException failure) { ConduitLog.warn("shutdown: " + failure.getMessage()); }
    });
    CoreCommands.register(runtime);
    // The one bootstrap an optional layer gets: the Velocity adapter, when it is on the classpath, is
    // handed the native API and registers its plugin format there (PluginManager#registerLoader).
    // Found by name so that core never imports a Velocity type.
    try {
      Class<?> boot = Class.forName("gg.tame.conduit.compat.velocity.VelocityBoot");
      boot.getMethod("install", gg.tame.conduit.api.ConduitProxy.class).invoke(null, runtime);
    } catch (ClassNotFoundException ignored) {
    } catch (ReflectiveOperationException exception) {
      ConduitLog.error("Velocity compatibility layer failed to install", exception);
    }
  }
  public ConduitRuntime runtime() { return runtime; }
  /** Connections whose worker has not returned yet; zero means every socket and thread is back. */
  public int activeConnections() { return connections.get(); }
  public void probeBackends() { runtime.selector().probeAll(); }
  public int port() throws IOException { return ((java.net.InetSocketAddress) listener.getLocalAddress()).getPort(); }
  public void serve() throws IOException {
    running = true;
    accepting = true;
    try { runtime.pluginRuntime().loadAll(); } catch (Exception exception) { ConduitLog.error("plugin load failed", exception); }
    runtime.started();
    try (var workers = Executors.newThreadPerTaskExecutor(SocketThreads.factory())) {
      while (running) {
        SocketChannel client;
        try {
          client = listener.accept();
        } catch (IOException failure) {
          // One connection failing to arrive is not the listener going away. A peer that resets
          // between the SYN and the accept, or a momentary descriptor shortage, ended the accept
          // loop and with it every future join: the process stayed up serving nobody. The loop now
          // ends only when the socket it is accepting on has actually gone.
          if (!running || !listener.isOpen()) break;
          ConduitLog.warn("Accept failed, still listening: " + failure);
          // Whatever is refusing connections is usually still refusing them a moment later, and a
          // failure that returns instantly would otherwise spin this thread at full speed.
          try { Thread.sleep(10); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
          continue;
        }
        if (client == null) continue;
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
    }
    // ProxyShutdownEvent is close()'s to fire, before it disables plugins. Fired here it raced
    // that disable, and plugins were usually gone before they heard the proxy was stopping.
  }
  private void handle(SocketChannel channel) {
    ConnectionThrottle.LeaseHolder leaseHolder = new ConnectionThrottle.LeaseHolder();
    InetAddress remote = null;
    try (Socket client = channel.socket()) {
      remote = client.getInetAddress();
      if (runtime.security().botFilter().isBlocked(remote)) {
        return;
      }
      ConnectionThrottle.Decision decision = runtime.security().throttle().tryAdmit(remote, leaseHolder);
      if (decision == ConnectionThrottle.Decision.THROTTLED) {
        return;
      }
      int handshakeTimeout = runtime.security().botFilter().settings().handshakeTimeoutMs();
      client.setSoTimeout(handshakeTimeout);
      PacketTransport transport = new PacketTransport(client);
      byte[] firstPacket;
      try {
        firstPacket = transport.read(configuration.maxFrameBytes());
      } catch (SocketTimeoutException timeout) {
        runtime.security().botFilter().recordSuspicious(remote, "idle");
        ConduitMetrics.current().malformedProtocol();
        return;
      } catch (IOException io) {
        runtime.security().botFilter().recordSuspicious(remote, "read-fail");
        ConduitMetrics.current().malformedProtocol();
        return;
      }
      // The timeout stays on for the rest of the handshake-to-play exchange. Cleared here, a
      // connection that sent a valid handshake and then nothing at all kept its worker thread, its
      // connection slot and its throttle lease for as long as it cared to hold the socket open.
      Handshake handshake;
      try {
        handshake = Handshake.decode(firstPacket);
        // Reject pathological FML host markers early without treating them as bots.
        gg.tame.conduit.modded.FmlAddressMarkers.parse(handshake.requestedHost());
      } catch (IllegalArgumentException | IOException malformed) {
        runtime.security().botFilter().recordSuspicious(remote, "malformed-handshake");
        ConduitMetrics.current().malformedProtocol();
        return;
      }
      runtime.security().botFilter().recordValidHandshake(remote);
      ProtocolSession session = new ProtocolSession(); session.acceptHandshake(handshake.nextState());
      // A protocol Conduit has no table for still gets an answer. Status is negotiated in the
      // same few packets on every version Conduit could be asked about, and answering it with a
      // neighbouring table is how a client too old or too new for this proxy sees a server that
      // says so in its list, rather than a connection that resets with no explanation at all.
      ProtocolDefinition protocol = ProtocolDefinition.hasCodec(handshake.protocolVersion())
          ? ProtocolDefinition.forVersion(handshake.protocolVersion())
          : statusFallbackProtocol();
      if (handshake.nextState() == 1) {
        runtime.security().botFilter().recordStatusPing(remote);
        serveStatus(transport, protocol, handshake, (java.net.InetSocketAddress) client.getRemoteSocketAddress());
        return;
      }
      if (!ProtocolDefinition.hasCodec(handshake.protocolVersion())) {
        // Login needs the client's own table: the proxy reads that client's packets on its own
        // behalf long before any translator is chosen, and cannot do so from a neighbour's ids.
        try {
          transport.write(LoginDisconnect.encode(protocol, "Unsupported Minecraft version."));
        } catch (IOException ignored) { }
        throw new IOException("unsupported Minecraft protocol: " + handshake.protocolVersion());
      }
      if (!runtime.versionGate().allows(handshake.protocolVersion())) {
        try { transport.write(LoginDisconnect.encode(protocol, runtime.versionGate().kickMessage())); } catch (IOException ignored) { }
        return;
      }
      byte[] loginStart = transport.read(configuration.maxFrameBytes());
      LoginPipeline pipeline = new LoginPipeline(session, protocol); pipeline.observe(gg.tame.conduit.protocol.PacketDirection.CLIENT_TO_SERVER, loginStart);
      if (authenticator.mode() == AuthenticationMode.ONLINE) {
        try {
          authenticateOnline(transport, protocol, pipeline, remote.getHostAddress());
        } catch (AuthenticationException exception) {
          try { transport.write(LoginDisconnect.encode(protocol, "Failed to verify username!")); } catch (IOException ignored) { }
          throw new IOException(exception.getMessage(), exception);
        }
      }
      try (PlayerSession player = new PlayerSession(configuration, transport, protocol, session, pipeline, forwarder, runtime,
          handshake, firstPacket, loginStart, configuration.forwardedPlayerAddress().orElse(remote))) {
        if (runtime.maintenance().isActive() && !maintenanceBypass(player)) {
          try { transport.write(LoginDisconnect.encode(protocol, runtime.maintenance().kickMessage())); } catch (IOException ignored) { }
          return;
        }
        PlayerLoginEvent login = runtime.events().fire(new PlayerLoginEvent(player));
        if (!login.allowed()) {
          // Still in Login: the reason goes out as a login disconnect, and no backend is dialled.
          player.disconnect(login.denyReason().orElseThrow());
          return;
        }
        if (player.authenticated()) runtime.events().fire(new PlayerAuthenticatedEvent(player));
        // The session clears the timeout itself, once its own login exchange with the client and
        // the backend is over: everything read from the client before that point is part of a
        // login, and a login that stops halfway must not park the worker holding it.
        player.play();
      }
    } catch (IOException exception) { ConduitLog.warn("Connection closed: " + exception.getMessage()); }
    catch (RuntimeException | Error unexpected) {
      // Only IOException was caught here, so anything else reached the worker pool's default
      // handler and disappeared: the client saw a socket close with no reason and the proxy
      // logged nothing at all. A missing packet id on a partially authored table raises an
      // unchecked exception, which is exactly the class of fault that most needs to be named.
      ConduitLog.error("Connection closed by an unhandled fault", unexpected);
      throw unexpected;
    }
    finally {
      runtime.security().throttle().release(leaseHolder.lease);
      connections.decrementAndGet();
    }
  }
  private boolean maintenanceBypass(PlayerSession player) {
    if (runtime.maintenance().settings().allowsUsername(player.username())) return true;
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
      // The client answers this one only after its own round trip to the session service, which is
      // slower than anything else in a login and has nothing to do with a stalling connection.
      transport.setReadTimeoutMillis(ENCRYPTION_RESPONSE_TIMEOUT_MS);
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
  /**
   * A table to answer a status ping with when the client's own protocol has none.
   *
   * <p>Any table works for the exchange itself &mdash; handshake, request, ping &mdash; so this
   * picks the oldest one Conduit has, because the ids it uses are the ones that have changed least
   * across the range and an old client is the likelier caller.
   */
  private static ProtocolDefinition statusFallbackProtocol() {
    return ProtocolDefinition.all().values().stream()
        .min(java.util.Comparator.comparingInt(d -> d.version().number()))
        .orElseThrow(() -> new IllegalStateException("no protocol tables are registered"));
  }

  /** Status sample size: what the vanilla server sends, and about what the client's tooltip shows. */
  private static final int STATUS_SAMPLE = 12;

  private void serveStatus(PacketTransport client, ProtocolDefinition protocol, Handshake handshake,
                           java.net.InetSocketAddress remote) throws IOException {
    byte[] request = client.read(configuration.maxFrameBytes());
    StatusResponder.checkRequest(protocol, request);
    int clientProtocol = handshake.protocolVersion();
    // Read per ping from the runtime's configuration, which a reload replaces, not the one this
    // listener started with.
    var status = runtime.configuration().status();
    Text description = status.motd();
    String versionName = "Conduit " + protocol.version().displayName();
    int advertised = protocol.version().number();
    if (runtime.maintenance().isActive()) {
      description = Text.of(runtime.maintenance().motd());
    }
    if (runtime.versionGate().isEnabled() && !runtime.versionGate().allows(clientProtocol)) {
      versionName = runtime.versionGate().pingVersionName(clientProtocol);
      advertised = runtime.versionGate().statusProtocolAdvertisement(clientProtocol).orElse(clientProtocol);
      if (!runtime.maintenance().isActive()) description = Text.of(runtime.versionGate().kickMessage());
    }
    var online = runtime.players().all();
    List<ServerListPingEvent.SamplePlayer> sample = online.stream().limit(STATUS_SAMPLE)
        .map(player -> new ServerListPingEvent.SamplePlayer(player.username(), player.uniqueId())).toList();
    String host = gg.tame.conduit.modded.FmlAddressMarkers.parse(handshake.requestedHost()).cleanHost();
    ServerListPingEvent ping = runtime.events().fire(new ServerListPingEvent(remote,
        host.isEmpty() ? Optional.empty() : Optional.of(host), handshake.requestedPort(), clientProtocol, description,
        status.displayMaxPlayers(), online.size(), sample, versionName, advertised, status.favicon()));
    // A client left with no answer at all shows the server as unreachable, which is what a plugin
    // cancelling this asks for.
    if (ping.cancelled()) return;
    client.write(StatusResponder.response(protocol, request, ping));
    client.write(StatusResponder.pong(protocol, client.read(configuration.maxFrameBytes())));
  }
  /**
   * Synchronized because ConduitProxy#shutdown closes from its own thread while the owner's
   * try-with-resources closes too once serve() returns; the second waits for the first rather than
   * disabling plugins under a graceful shutdown still moving players.
   */
  @Override public synchronized void close() throws IOException {
    if (running) {
      runtime.shutdownGracefully(() -> accepting = false);
    }
    running = false;
    accepting = false;
    listener.close();
    runtime.close();
  }
}
