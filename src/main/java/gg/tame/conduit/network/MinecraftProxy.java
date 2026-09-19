// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.network;

import gg.tame.conduit.api.event.player.GameProfileRequestEvent;
import gg.tame.conduit.api.event.player.PlayerAuthenticatedEvent;
import gg.tame.conduit.api.event.player.PlayerDisconnectEvent.LoginStatus;
import gg.tame.conduit.api.event.player.PlayerLoginEvent;
import gg.tame.conduit.api.event.player.PlayerPreLoginEvent;
import gg.tame.conduit.api.event.player.PlayerSetupEvent;
import gg.tame.conduit.api.event.proxy.ServerListPingEvent;
import gg.tame.conduit.api.permission.PermissionProvider;
import gg.tame.conduit.api.player.GameProfile;
import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.auth.Authenticators;
import gg.tame.conduit.auth.AuthenticationException;
import gg.tame.conduit.auth.PlayerAuthenticator;
import gg.tame.conduit.auth.SessionQuery;
import gg.tame.conduit.command.CoreCommands;
import gg.tame.conduit.command.Permissions;
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
  /**
   * How long after its handshake a connection may still be read from before it reaches Play, or its
   * status exchange ends. Long enough for the encryption response, which waits on the client's own
   * round trip to Mojang, and for a first backend or two to refuse. The
   * {@code conduit.loginDeadlineMillis} system property overrides it, so tests can use a short one.
   */
  private static final long LOGIN_DEADLINE_MS = 60_000;
  /**
   * How long a login waits for the player's previous session to finish ending. That session's
   * PlayerDisconnectEvent listeners take part of it, and a Velocity plugin gets 10 s for its own.
   */
  private static final long CONFLICT_WAIT_MS = 12_000;
  private final ServerSocketChannel listener;
  private final ConduitConfiguration configuration;
  private final PlayerInfoForwarder forwarder;
  private final PlayerAuthenticator authenticator;
  /** The session check an offline proxy runs for a connection a plugin forced online; built on first use. */
  private volatile PlayerAuthenticator forcedOnline;
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
      // The jar carries the Velocity API with the adapter, so this is a class path assembled some
      // other way. An optional feature simply being absent is one line, not a stack trace at ERROR
      // that reads as Conduit having crashed.
      String missing = missingVelocityApi(exception);
      if (missing != null) {
        ConduitLog.info("Velocity plugin support is off: the Velocity API is not on the class path ("
            + missing + "). Conduit's own plugins are unaffected; start the conduit jar itself, which"
            + " carries the Velocity API, to load Velocity plugins.");
      } else {
        ConduitLog.error("Velocity compatibility layer failed to install", exception);
      }
    }
  }

  /**
   * The Velocity class the compatibility layer could not find, or null when it failed for some other
   * reason -- which is still a real error and still gets a stack trace.
   */
  private static String missingVelocityApi(Throwable thrown) {
    for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
      boolean absent = cause instanceof NoClassDefFoundError || cause instanceof ClassNotFoundException;
      String name = cause.getMessage() == null ? "" : cause.getMessage().replace('/', '.');
      if (absent && name.startsWith("com.velocitypowered.")) return name;
      if (cause.getCause() == cause) break;
    }
    return null;
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
    // Not try-with-resources. ExecutorService#close() waits for every submitted task without a
    // bound, and a task here is a whole player connection: one session parked in a socket read kept
    // serve() from returning, main from returning and the JVM from exiting, which on Windows is a
    // console window that never closes after /conduit shutdown. The drain below is bounded instead.
    var workers = Executors.newThreadPerTaskExecutor(SocketThreads.factory());
    try {
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
    } finally {
      drain(workers);
    }
    // ProxyShutdownEvent is close()'s to fire, before it disables plugins. Fired here it raced
    // that disable, and plugins were usually gone before they heard the proxy was stopping.
  }

  /**
   * Gives the connection workers the graceful-shutdown budget to finish, then stops waiting.
   *
   * <p>Players have already been kicked or moved by then, so a worker still running is one whose
   * socket is not answering -- a half-open connection, or a peer that stopped reading. Waiting on it
   * is waiting on a dead peer's TCP timeout, which is not Conduit's to spend. The threads are
   * daemons on Windows and virtual elsewhere, so whatever is left does not hold the JVM open.
   */
  private void drain(java.util.concurrent.ExecutorService workers) {
    long budget = Math.max(0, runtime.gracefulShutdown().settings().timeoutMs());
    workers.shutdown();
    try {
      if (!workers.awaitTermination(budget, java.util.concurrent.TimeUnit.MILLISECONDS)) {
        int left = connections.get();
        // Interrupts the blocking reads, which is what a virtual thread needs to unpark.
        workers.shutdownNow();
        if (!workers.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS) && left > 0) {
          ConduitLog.warn("Stopped waiting for " + left + " connection"
              + (left == 1 ? "" : "s") + " that did not close within " + budget + "ms.");
        }
      }
    } catch (InterruptedException interrupted) {
      workers.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
  private void handle(SocketChannel channel) {
    ConnectionThrottle.LeaseHolder leaseHolder = new ConnectionThrottle.LeaseHolder();
    InetAddress remote = null;
    Socket client = channel.socket();
    PacketTransport[] opened = new PacketTransport[1];
    ClientLoginMessages[] loginMessages = new ClientLoginMessages[1];
    try {
      remote = client.getInetAddress();
      // Before anything judges this connection by its address: behind a reverse proxy the socket
      // wears that service's address, and the header at the front of the stream is what says whose
      // connection it really is. Read first, so the throttle, the bot filter and every ban that
      // follows are applied to the player rather than to the service in front of them.
      java.net.InetSocketAddress declared = null;
      if (configuration.proxyProtocol()) {
        try {
          java.util.Optional<java.net.InetSocketAddress> header =
              ProxyProtocol.read(client.getInputStream());
          if (header.isPresent()) {
            declared = header.get();
            remote = declared.getAddress();
          }
        } catch (IOException malformed) {
          // With proxy-protocol on, a connection without a usable header cannot be read at all:
          // there is no way to know where the header stopped and the handshake began.
          ConduitMetrics.current().malformedProtocol();
          return;
        }
      }
      if (runtime.security().botFilter().isBlocked(remote)) {
        return;
      }
      ConnectionThrottle.Decision decision = runtime.security().throttle().tryAdmit(remote, leaseHolder);
      if (decision == ConnectionThrottle.Decision.THROTTLED) {
        return;
      }
      int handshakeTimeout = runtime.security().botFilter().settings().handshakeTimeoutMs();
      client.setSoTimeout(handshakeTimeout);
      PacketTransport transport = opened[0] = new PacketTransport(client);
      if (declared != null) transport.declareRemote(declared);
      transport.setReadDeadline(Long.getLong("conduit.loginDeadlineMillis", LOGIN_DEADLINE_MS));
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
      // Every ping comes through here, so the event is not even built unless someone listens.
      if (runtime.events().listening(gg.tame.conduit.api.event.proxy.ConnectionHandshakeEvent.class)) {
        runtime.events().fire(new gg.tame.conduit.api.event.proxy.ConnectionHandshakeEvent(
            declared != null ? declared : (java.net.InetSocketAddress) client.getRemoteSocketAddress(),
            handshake.virtualHost(), handshake.protocolVersion(),
            handshake.nextState() == 1 ? gg.tame.conduit.api.event.proxy.ConnectionHandshakeEvent.Intent.STATUS
                : handshake.nextState() == Handshake.TRANSFER ? gg.tame.conduit.api.event.proxy.ConnectionHandshakeEvent.Intent.TRANSFER
                : gg.tame.conduit.api.event.proxy.ConnectionHandshakeEvent.Intent.LOGIN));
      }
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
        serveStatus(transport, protocol, handshake,
            declared != null ? declared : (java.net.InetSocketAddress) client.getRemoteSocketAddress());
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
      InetAddress playerAddress = configuration.forwardedPlayerAddress().orElse(remote);
      ClientLoginMessages messages = loginMessages[0] = new ClientLoginMessages(protocol);
      // Plugins' first say, on nothing but what the client claims: a refusal here costs no encryption
      // and no session-server round trip, and a login refused here never becomes a Player.
      PlayerPreLoginEvent preLogin = runtime.events().fire(new PlayerPreLoginEvent(pipeline.player().username(),
          claimedUniqueId(pipeline, protocol), playerAddress, handshake.virtualHost(), handshake.protocolVersion(), handshake.nextState() == Handshake.TRANSFER,
          messages::send));
      if (!preLogin.allowed()) {
        try { transport.write(LoginDisconnect.encode(protocol, preLogin.denyReason().orElseThrow())); } catch (IOException ignored) { }
        return;
      }
      if (checksSession(preLogin.authentication())) {
        try {
          authenticateOnline(transport, protocol, pipeline, remote.getHostAddress(), sessionChecker());
        } catch (AuthenticationException exception) {
          try { transport.write(LoginDisconnect.encode(protocol, "Failed to verify username!")); } catch (IOException ignored) { }
          throw new IOException(exception.getMessage(), exception);
        }
      }
      // What PlayerPreLoginEvent's listeners asked the client, now that the proxy's own exchange with it
      // is done: answered before anything else is decided, so later listeners can go by the answers.
      messages.exchange(transport, configuration.maxFrameBytes());
      // Everything above is the proxy protecting itself, and no plugin can see or override any of it.
      // From here the client is a Player: plugins set it up first, and only then is anything decided
      // about it, so that a permission plugin already knows the player when maintenance asks.
      requestGameProfile(pipeline, playerAddress, handshake, messages);
      try (PlayerSession player = new PlayerSession(configuration, transport, protocol, session, pipeline, forwarder, runtime,
          handshake, firstPacket, loginStart, playerAddress)) {
        if (!claimIdentity(player, transport, protocol)) return;
        try {
          runtime.events().fire(new PlayerSetupEvent(player));
          admit(player, transport, protocol, messages);
        } finally {
          // Whatever ended the login, plugins that set something up for this player hear it once;
          // every path that knows why has already said so, and this is for the ones that threw.
          player.leave(LoginStatus.CANCELLED_BY_PROXY);
        }
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
      if (loginMessages[0] != null) loginMessages[0].close();
      // Through the transport once there is one: it ends the output first, so a client still sending
      // reads the last thing it was sent -- a disconnect's reason -- before the socket goes.
      if (opened[0] != null) opened[0].close();
      else try { client.close(); } catch (IOException ignored) { }
      runtime.security().throttle().release(leaseHolder.lease);
      connections.decrementAndGet();
    }
  }
  /**
   * One player, one session: the last of the proxy's own checks, before any plugin hears of the
   * player. A login of a player who is already connected is refused, or, with kick-existing-players,
   * ends the existing session and waits for it to be gone. A session already ending is waited for
   * either way -- a player who quits and rejoins at once is not refused over their own last session
   * -- and so is its PlayerDisconnectEvent, so a plugin keyed by UUID hears one session end before the
   * next is set up. False when this login was refused.
   */
  private boolean claimIdentity(PlayerSession player, PacketTransport transport, ProtocolDefinition protocol) {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(CONFLICT_WAIT_MS);
    PlayerSession holder;
    while ((holder = runtime.playerManager().claim(player)) != null) {
      if (runtime.configuration().authentication().kickExistingPlayers()) {
        if (!holder.closed()) holder.displace();
      } else if (!holder.closed()) {
        return refuseDuplicate(player, transport, protocol, "You are already connected to this network.");
      }
      long left = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
      try {
        if (left <= 0 || !runtime.playerManager().awaitRelease(holder, left)) {
          return refuseDuplicate(player, transport, protocol, "Your previous connection is still closing. Please try again.");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return true;
  }
  private static boolean refuseDuplicate(PlayerSession player, PacketTransport transport, ProtocolDefinition protocol, String message) {
    ConduitLog.info("Refused a second login of " + player.username() + " (" + player.uniqueId() + "): " + message);
    try { transport.write(LoginDisconnect.encode(protocol, message)); } catch (IOException ignored) { }
    return false;
  }
  /** The proxy's and the plugins' decisions on a player who is set up: maintenance, then PlayerLoginEvent. */
  private void admit(PlayerSession player, PacketTransport transport, ProtocolDefinition protocol, ClientLoginMessages messages) throws IOException {
    // Setup listeners may hold the login for seconds -- a permission plugin loading the player from
    // its database -- and nothing is decided for a client that gave up meanwhile.
    if (over(player, transport)) return;
    if (runtime.maintenance().isActive() && !maintenanceBypass(player)) {
      try { transport.write(LoginDisconnect.encode(protocol, runtime.maintenance().kickMessage())); } catch (IOException ignored) { }
      player.leave(LoginStatus.CANCELLED_BY_PROXY);
      return;
    }
    PlayerLoginEvent login = runtime.events().fire(new PlayerLoginEvent(player));
    if (!login.allowed()) {
      // Still in Login: the reason goes out as a login disconnect, and no backend is dialled.
      player.disconnect(login.denyReason().orElseThrow());
      player.leave(LoginStatus.CANCELLED_BY_PROXY);
      return;
    }
    if (player.authenticated()) runtime.events().fire(new PlayerAuthenticatedEvent(player));
    // What plugins asked the client since the first exchange, answered before a backend is dialled.
    // The login is decided: nothing more can be asked, and the ids stay clear of the backend's queries.
    messages.exchange(transport, configuration.maxFrameBytes());
    messages.close();
    // Login listeners may have held it as long. A client that gave up meanwhile would otherwise still
    // be logged in to a backend, and reported by a PlayerPostLoginEvent as having joined.
    if (over(player, transport)) return;
    // The shutdown's sweep of online players cannot see a login still being decided.
    if (runtime.shuttingDown()) {
      player.disconnect(runtime.gracefulShutdown().message());
      player.leave(LoginStatus.CANCELLED_BY_PROXY);
      return;
    }
    // The session clears the timeout itself, once its own login exchange with the client and
    // the backend is over: everything read from the client before that point is part of a
    // login, and a login that stops halfway must not park the worker holding it.
    player.play();
  }
  /** Whether the login ended while listeners held it, and if so tells plugins how. */
  private static boolean over(PlayerSession player, PacketTransport transport) {
    // A listener that kicked the player, rather than denying the login, has closed the session.
    if (player.lifecycle() == gg.tame.conduit.session.SessionLifecycle.CLOSED) {
      player.leave(LoginStatus.CANCELLED_BY_PROXY);
      return true;
    }
    if (transport.hungUp()) {
      player.leave(LoginStatus.CANCELLED_BY_USER);
      return true;
    }
    return false;
  }
  /**
   * The allowlist first: it is the operator's own list, and has to work when the permission plugin
   * is broken or gone. Then the provider in force, read once, so a plugin disabled halfway through
   * cannot leave the permissive default answering the second question.
   */
  private boolean maintenanceBypass(PlayerSession player) {
    if (runtime.maintenance().settings().allowsUsername(player.username())) return true;
    PermissionProvider provider = runtime.permissions();
    try {
      // A provider answering the same for everyone must not decide who gets in.
      return provider.manages(player)
          && (provider.hasPermission(player, Permissions.MAINTENANCE_BYPASS) || provider.hasPermission(player, Permissions.CONDUIT_ADMIN));
    } catch (RuntimeException | LinkageError failure) {
      ConduitLog.error("permission provider failed deciding whether " + player.username() + " may bypass maintenance; refused", failure);
      return false;
    }
  }
  /**
   * Lets plugins replace the settled profile before anything else sees it. The login itself is not
   * theirs to redo: whether the session server vouched for the connection stays as it was.
   */
  private void requestGameProfile(LoginPipeline pipeline, InetAddress playerAddress, Handshake handshake, ClientLoginMessages messages) {
    var settled = pipeline.player();
    GameProfile original = new GameProfile(settled.uniqueId(), settled.username(), settled.properties().stream()
        .map(property -> new GameProfile.Property(property.name(), property.value(), property.signature())).toList());
    GameProfile chosen = runtime.events().fire(new GameProfileRequestEvent(settled.username(), playerAddress, handshake.virtualHost(),
        handshake.protocolVersion(), handshake.nextState() == Handshake.TRANSFER, settled.authenticated(), original, messages::send)).gameProfile();
    if (chosen.equals(original)) return;
    pipeline.replace(new gg.tame.conduit.login.PlayerProfile(chosen.uniqueId(), chosen.name(), chosen.properties().stream()
        .map(property -> new gg.tame.conduit.login.ProfileProperty(property.name(), property.value(), property.signature())).toList(),
        settled.authenticated()));
    ConduitLog.info("A plugin replaced " + settled.username() + " (" + settled.uniqueId() + ")'s profile: "
        + pipeline.player().summary());
  }
  /** Whether this connection is checked with the session server: the proxy's mode, unless a PlayerPreLoginEvent forced one. */
  private boolean checksSession(PlayerPreLoginEvent.Authentication chosen) {
    return switch (chosen) {
      case FORCE_ONLINE -> true;
      case FORCE_OFFLINE -> false;
      case PROXY_DEFAULT -> authenticator.mode() == AuthenticationMode.ONLINE;
    };
  }
  /**
   * The online-mode check for a connection that has one. An offline proxy that a plugin asked to
   * check a connection builds one from its [authentication] settings, held to online mode's rule for
   * the session URL: an offline proxy's URL was never checked, and a check over plain HTTP proves
   * nothing to anyone on the path.
   */
  private PlayerAuthenticator sessionChecker() throws AuthenticationException {
    if (authenticator.mode() == AuthenticationMode.ONLINE) return authenticator;
    PlayerAuthenticator forced = forcedOnline;
    if (forced != null) return forced;
    if (!configuration.authentication().sessionUrlSecure()) {
      ConduitLog.error("A plugin forced online mode, but authentication.session-url is not HTTPS or loopback; the login is refused");
      throw new AuthenticationException("session-url is not secure");
    }
    return forcedOnline = new gg.tame.conduit.auth.MojangSessionAuthenticator(configuration.authentication());
  }
  /** The UUID in the client's Login Start, when it sent one; otherwise the proxy derived it from the name. */
  private static Optional<java.util.UUID> claimedUniqueId(LoginPipeline pipeline, ProtocolDefinition protocol) {
    var profile = pipeline.player();
    // ponytail: a 1.19.1-1.20.1 client that sends exactly its offline UUID reads as having sent none.
    boolean sent = protocol.capabilities().loginStartUuid()
        || !profile.uniqueId().equals(gg.tame.conduit.login.LoginStart.offlineUuid(profile.username()));
    return sent ? Optional.of(profile.uniqueId()) : Optional.empty();
  }
  private void authenticateOnline(PacketTransport transport, ProtocolDefinition protocol, LoginPipeline pipeline, String address,
                                  PlayerAuthenticator authenticator) throws IOException, AuthenticationException {
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
