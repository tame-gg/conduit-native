// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.runtime;

import gg.tame.conduit.Conduit;
import gg.tame.conduit.api.ConduitProxy;
import gg.tame.conduit.api.permission.PermissionProvider;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.api.player.PlayerLookup;
import gg.tame.conduit.api.plugin.PluginManager;
import gg.tame.conduit.api.scheduler.Scheduler;
import gg.tame.conduit.api.server.RegisteredServer;
import gg.tame.conduit.api.server.ServerManager;
import gg.tame.conduit.command.CommandManager;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConfigMigrator;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.config.ConfigurationLoader;
import gg.tame.conduit.config.OpsSettings;
import gg.tame.conduit.event.ConduitEventManager;
import gg.tame.conduit.health.BackendHealthService;
import gg.tame.conduit.ops.GracefulShutdown;
import gg.tame.conduit.ops.MaintenanceService;
import gg.tame.conduit.permission.DefaultPermissionProvider;
import gg.tame.conduit.plugin.ConduitPluginManager;
import gg.tame.conduit.plugin.PluginCatalog;
import gg.tame.conduit.routing.BackendSelector;
import gg.tame.conduit.routing.ServerRegistry;
import gg.tame.conduit.scheduler.ConduitScheduler;
import gg.tame.conduit.security.SecurityService;
import gg.tame.conduit.modded.ModdedService;
import gg.tame.conduit.session.PlayerManager;
import gg.tame.conduit.session.TrackedPlayer;
import gg.tame.conduit.version.VersionGate;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class ConduitRuntime implements ConduitProxy, AutoCloseable {
  private final CommandManager commands;
  private final PlayerManager players;
  private final BackendHealthService health;
  private final BackendSelector selector;
  private final MaintenanceService maintenance;
  private final gg.tame.conduit.ops.BanList bans;
  private final gg.tame.conduit.ops.Whitelist whitelist;
  private final gg.tame.conduit.ops.ProtectedPlayers protectedPlayers;
  private final gg.tame.conduit.messaging.BungeeCordMessages bungeeCord =
      new gg.tame.conduit.messaging.BungeeCordMessages(this);
  private final VersionGate versionGate;
  private final GracefulShutdown gracefulShutdown;
  private final SecurityService security;
  private final ModdedService modded;
  private final ConduitEventManager events = new ConduitEventManager();
  private final ConduitScheduler scheduler = new ConduitScheduler();
  private final PermissionProvider DEFAULT_PERMISSIONS = new DefaultPermissionProvider(() -> configuration().ops().permissions());
  /** Provider and owner change together, so a disable can never reset another plugin's provider. */
  private volatile PermissionGrant permissions = new PermissionGrant(null, DEFAULT_PERMISSIONS);
  private final gg.tame.conduit.command.ConsoleCommandSource console = new gg.tame.conduit.command.ConsoleCommandSource();
  private volatile InetSocketAddress boundAddress;
  private volatile Runnable shutdownHook;
  private final java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
  private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
  private final ServerViews servers;
  private final PlayerViews playerViews;
  private final ConduitPluginManager plugins;
  private final PluginCatalog pluginCatalog = new PluginCatalog();
  private final Path pluginsDirectory;
  private final Path configDirectory;
  private volatile Path configPath;
  private volatile ConduitConfiguration configuration;
  private final long startedAtNanos = System.nanoTime();

  public ConduitRuntime(ConduitConfiguration configuration, Path pluginsDirectory) {
    this(configuration, pluginsDirectory, pluginsDirectory);
  }

  public ConduitRuntime(ConduitConfiguration configuration, Path pluginsDirectory, Path configDirectory) {
    this.configuration = configuration;
    this.pluginsDirectory = pluginsDirectory;
    this.configDirectory = configDirectory == null ? Path.of(".") : configDirectory;
    this.health = new BackendHealthService(new ServerRegistry(configuration), configuration.health());
    this.selector = new BackendSelector(configuration, health);
    this.maintenance = new MaintenanceService(this.configDirectory, configuration.maintenance());
    // Both are operational state rather than configuration: they are read from their own files and
    // written the moment a command changes them, so nothing here is touched by a reload.
    this.bans = new gg.tame.conduit.ops.BanList(this.configDirectory);
    this.whitelist = new gg.tame.conduit.ops.Whitelist(this.configDirectory);
    this.protectedPlayers = new gg.tame.conduit.ops.ProtectedPlayers(this.configDirectory);
    this.versionGate = new VersionGate(configuration.versions());
    this.gracefulShutdown = new GracefulShutdown(configuration.shutdown());
    this.security = new SecurityService(configuration.security());
    this.modded = new ModdedService(configuration.modded());
    this.commands = new CommandManager(events);
    this.players = new PlayerManager();
    this.commands.onChanged(this::refreshCommandTrees);
    this.boundAddress = configuration.listener();
    this.servers = new ServerViews(this, selector.registry(), selector);
    this.playerViews = new PlayerViews(players);
    this.plugins = new ConduitPluginManager(pluginsDirectory, this, events, scheduler, commands);
    health.start();
    gg.tame.conduit.viaversion.ConduitViaBootstrap.start(
        this.configDirectory,
        gg.tame.conduit.Conduit.VERSION,
        configuration.translation());
  }

  public void bindConfigPath(Path path) { this.configPath = path; }
  public Path configPath() { return configPath; }
  public Path configDirectory() { return configDirectory; }
  public ConduitConfiguration configuration() { return configuration; }
  public BackendHealthService health() { return health; }
  public MaintenanceService maintenance() { return maintenance; }
  public gg.tame.conduit.ops.BanList bans() { return bans; }
  public gg.tame.conduit.ops.Whitelist whitelist() { return whitelist; }
  public gg.tame.conduit.ops.ProtectedPlayers protectedPlayers() { return protectedPlayers; }
  /**
   * Records whether {@code player} is out of other staff's reach, for a {@code /gban} made while they
   * are offline, when the provider cannot be asked about them. Called at login and as they leave, so
   * a node granted or taken mid-session is seen by the time it could matter. A provider that fails is
   * left unanswered: what was last recorded stands.
   */
  public void noteProtection(Player player) {
    try {
      boolean held = gg.tame.conduit.command.Permissions.allows(player, gg.tame.conduit.command.Permissions.GKICK)
          || gg.tame.conduit.command.Permissions.allows(player, gg.tame.conduit.command.Permissions.GBAN)
          || gg.tame.conduit.command.Permissions.allows(player, gg.tame.conduit.command.Permissions.PUNISH_EXEMPT);
      protectedPlayers.remember(player.uniqueId(), player.username(), held);
    } catch (RuntimeException | LinkageError failed) {
      gg.tame.conduit.log.ConduitLog.warn("permission provider failed deciding whether " + player.username() + " is protected from /gban: " + failed);
    }
  }
  /** The channel backend plugins reach the proxy on; see BungeeCordMessages. */
  public gg.tame.conduit.messaging.BungeeCordMessages bungeeCord() { return bungeeCord; }
  public VersionGate versionGate() { return versionGate; }
  public GracefulShutdown gracefulShutdown() { return gracefulShutdown; }
  public SecurityService security() { return security; }
  public ModdedService modded() { return modded; }
  public PluginCatalog pluginCatalog() { return pluginCatalog; }
  public long uptimeMillis() { return Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000L); }
  public CommandManager commandManager() { return commands; }
  public PlayerManager playerManager() { return players; }
  public BackendSelector selector() { return selector; }
  private final Object connectionSelectorLock = new Object();
  private gg.tame.conduit.network.ConnectionSelector connectionSelector;
  /**
   * Where playing connections are watched. Opened at start, and the only place a playing connection
   * is ever read: there is no second path, so what a session costs between packets is a file
   * descriptor and its buffers on every platform, not a thread on some of them.
   *
   * <p>It used to be built on the first player and to be allowed to fail, with a session that could
   * not be watched keeping a thread per socket. That left the population a proxy could hold
   * depending on whether a {@link java.nio.channels.Selector} had opened hours earlier, which is
   * not a thing to find out at a thousand players. A selector that will not open means this JDK
   * cannot do non-blocking I/O at all; the proxy says so and does not start.
   */
  public gg.tame.conduit.network.ConnectionSelector connectionSelector() throws IOException {
    synchronized (connectionSelectorLock) {
      if (closed.get()) throw new IOException("the proxy is shutting down");
      if (connectionSelector == null) connectionSelector = new gg.tame.conduit.network.ConnectionSelector();
      return connectionSelector;
    }
  }
  public ConduitEventManager eventBus() { return events; }
  @Override public String version() { return Conduit.VERSION; }
  @Override public int apiVersion() { return Conduit.API_VERSION; }
  @Override public PlayerLookup players() { return playerViews; }
  @Override public ServerManager servers() { return servers; }
  @Override public CommandManager commands() { return commands; }
  @Override public ConduitEventManager events() { return events; }
  @Override public PluginManager plugins() { return plugins; }
  @Override public Scheduler scheduler() { return scheduler; }
  @Override public PermissionProvider permissions() { return permissions.provider(); }
  /** The channels plugins listen on, announced to every backend alongside Conduit's own. */
  private final java.util.Set<String> proxyChannels = java.util.concurrent.ConcurrentHashMap.newKeySet();
  @Override public void listenOnChannel(String channel) {
    if (channel == null || channel.isBlank()) throw new IllegalArgumentException("a channel is required");
    if (!proxyChannels.add(channel)) return;
    // The players already on a backend joined before this channel existed, so their backends are
    // told now; everyone who joins a backend later is told with the rest.
    for (Player player : players().all()) {
      if (player instanceof gg.tame.conduit.session.PlayerSession session) session.announceProxyChannels(java.util.List.of(channel));
    }
  }
  @Override public void stopListeningOnChannel(String channel) { if (channel != null) proxyChannels.remove(channel); }
  /** What plugins listen on, for a backend a player has just joined. */
  public java.util.Set<String> proxyChannels() { return java.util.Set.copyOf(proxyChannels); }
  @Override public void setPermissionProvider(gg.tame.conduit.api.plugin.Plugin owner, PermissionProvider provider) {
    synchronized (this) {
      if (owner == null || provider == null) throw new IllegalArgumentException("owner and provider are required");
      // Installed after its owner's release, a provider would answer every check for good, from a
      // plugin whose class loader was closed.
      if (released.contains(owner)) throw new IllegalStateException("plugin " + owner.description().id() + " is disabled");
      permissions = new PermissionGrant(owner, provider);
    }
    // Outside the lock: this writes to every client's socket. Who may see which command has just
    // changed for everyone connected, and the tree they hold says otherwise until they are told.
    refreshCommandTrees();
  }
  /** The provider the nodes were last shown to, so each one is asked about them once. */
  private final java.util.concurrent.atomic.AtomicReference<PermissionProvider> revealedTo = new java.util.concurrent.atomic.AtomicReference<>();
  /**
   * Asks the permissions plugin about every Conduit node, once per plugin, by way of {@code player},
   * and ignores the answers. A plugin such as LuckPerms offers in its editor only the nodes it has
   * seen checked, and some of Conduit's are checked only at the moment they matter --
   * conduit.punish.exempt when someone is kicked -- so an operator could not find them to grant. Once
   * is enough, since it remembers a node whoever it was checked for; every login would ask about
   * players nothing else needs to. Called at a login, on the login's thread: a player being logged in
   * is one the plugin has loaded, and a plugin that has just been removed is never asked.
   */
  public void revealNodes(Player player) {
    PermissionProvider provider = permissions.provider();
    if (player == null || provider == DEFAULT_PERMISSIONS) return;
    PermissionProvider before = revealedTo.get();
    if (before == provider || !revealedTo.compareAndSet(before, provider)) return;
    // The provider itself, not the player's current one: a provider replaced or released while
    // this runs is asked nothing more, and its replacement nothing it was not asked for.
    for (String node : gg.tame.conduit.command.Permissions.all()) {
      if (permissions.provider() != provider) return;
      try { provider.hasPermission(player, node); } catch (RuntimeException ignored) { }
    }
  }
  /** Whatever {@code plugin} installed stops answering, before its class loader closes under it. */
  public void pluginReleased(gg.tame.conduit.api.plugin.Plugin plugin) {
    boolean wasTheirs;
    synchronized (this) {
      released.add(plugin);
      wasTheirs = permissions.owner() == plugin;
      if (wasTheirs) permissions = new PermissionGrant(null, DEFAULT_PERMISSIONS);
    }
    if (wasTheirs) refreshCommandTrees();
  }
  /**
   * Declares the command tree again to every connected client, for the ones new enough to parse one.
   * A 1.13+ client is sent the tree on join and on a server switch and never in between, so without
   * this a command registered, or a permission granted, mid-session showed up only after a switch.
   *
   * <p>ponytail: this writes to each client in turn on the caller's thread, so a plugin registering
   * a command pays for the resend; hand it to a pool if a big network ever feels it.
   */
  public void refreshCommandTrees() {
    for (gg.tame.conduit.session.TrackedPlayer player : players.all()) {
      try { player.refreshCommands(); }
      catch (RuntimeException | LinkageError failed) {
        gg.tame.conduit.log.ConduitLog.error("could not resend the command tree to " + player.username(), failed);
      }
    }
  }
  /** Plugins released, which may not install a provider again. Guarded by {@code this}. */
  private final java.util.Set<gg.tame.conduit.api.plugin.Plugin> released =
      java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
  private record PermissionGrant(gg.tame.conduit.api.plugin.Plugin owner, PermissionProvider provider) {}
  @Override public Optional<Player> player(UUID uniqueId) { return playerViews.get(uniqueId); }
  @Override public Optional<Player> player(String username) { return playerViews.getByUsername(username); }
  @Override public gg.tame.conduit.api.command.CommandSource console() { return console; }
  @Override public InetSocketAddress boundAddress() { return boundAddress; }
  /** The listener's real address, which differs from the configured one when that asked for port 0. */
  public void bindListener(InetSocketAddress address) { if (address != null) this.boundAddress = address; }
  @Override public int compressionThreshold() { return configuration.compressionThreshold(); }
  @Override public boolean onlineMode() {
    return configuration.authentication().mode() == gg.tame.conduit.config.AuthenticationMode.ONLINE;
  }
  /** What {@link #shutdown()} runs: the owning listener's close, which kicks players before this closes. */
  public void onShutdownRequest(Runnable hook) { this.shutdownHook = hook; }
  @Override public void shutdown() {
    Runnable hook = shutdownHook;
    // Its own platform thread: the caller may be a listener on a player's connection, which the
    // shutdown is about to close, or a scheduler task, whose pool the shutdown is about to stop.
    Thread.ofPlatform().name("conduit-shutdown").start(() -> {
      try {
        if (hook != null) hook.run();
        else close();
      } catch (Exception | LinkageError failure) {
        gg.tame.conduit.log.ConduitLog.error("proxy shutdown failed", failure);
      }
    });
  }
  @Override public void shutdown(gg.tame.conduit.api.text.Text reason) {
    if (reason != null) gracefulShutdown.kickWith(reason);
    shutdown();
  }
  @Override public boolean shuttingDown() { return closed.get() || gracefulShutdown.isShuttingDown(); }
  /** Read per call, so a reload is seen at once. */
  @Override public gg.tame.conduit.api.server.ServerListDefaults serverListDefaults() {
    var status = configuration.status();
    return new gg.tame.conduit.api.server.ServerListDefaults(status.motd(), status.displayMaxPlayers(), status.favicon());
  }
  /** Fires ProxyStartEvent, once; the matching ProxyShutdownEvent comes from {@link #close()}. */
  public void started() {
    if (!started.compareAndSet(false, true)) return;
    // Opened here rather than on the first player, so a JDK that cannot give us one is a start that
    // fails with a reason rather than a proxy that quietly holds far fewer players than it says.
    try { connectionSelector(); }
    catch (IOException failed) {
      throw new IllegalStateException("Could not open the connection selector playing sessions are"
          + " read on; this JDK cannot do non-blocking I/O: " + failed.getMessage(), failed);
    }
    configuration.ops().metrics().prometheusAddress().ifPresent(address -> {
      // A metrics address that cannot be bound is reported and the proxy serves players regardless.
      try { metricsEndpoint = gg.tame.conduit.metrics.PrometheusEndpoint.start(address, this); }
      catch (IOException | RuntimeException failed) { gg.tame.conduit.log.ConduitLog.error("could not serve metrics on " + address + ": " + failed); }
    });
    events.fire(new gg.tame.conduit.api.event.proxy.ProxyStartEvent(this));
  }
  /** The Prometheus endpoint when one is configured and bound, for tests. */
  public Optional<gg.tame.conduit.metrics.PrometheusEndpoint> metricsEndpoint() { return Optional.ofNullable(metricsEndpoint); }
  private volatile gg.tame.conduit.metrics.PrometheusEndpoint metricsEndpoint;
  public ConduitPluginManager pluginRuntime() { return plugins; }
  public Optional<RegisteredServer> registered(String name) { return servers.getServer(name); }
  public boolean isMaintenanceActive() { return maintenance.isActive(); }

  public record ReloadResult(boolean applied, List<String> restartRequired, List<String> appliedLive, String error) {
    public ReloadResult {
      restartRequired = List.copyOf(restartRequired);
      appliedLive = List.copyOf(appliedLive);
    }
  }

  /** Fires ProxyReloadEvent after one that applied, outside the lock, so a listener can read the result. */
  public ReloadResult reload() {
    ReloadResult result = reloadConfiguration();
    if (result.applied()) events.fire(new gg.tame.conduit.api.event.proxy.ProxyReloadEvent(this));
    return result;
  }

  private synchronized ReloadResult reloadConfiguration() {
    if (configPath == null) return new ReloadResult(false, List.of(), List.of(), "No config path bound.");
    try {
      ConfigMigrator.migrate(configPath);
      ConduitConfiguration next = ConfigurationLoader.load(configPath);
      List<String> restart = new ArrayList<>();
      ConduitConfiguration current = this.configuration;
      if (!Objects.equals(current.listener(), next.listener())) restart.add("listener.host / listener.port");
      if (current.maxFrameBytes() != next.maxFrameBytes()) restart.add("listener.max-frame-bytes");
      if (current.compressionThreshold() != next.compressionThreshold()) restart.add("listener.compression-threshold");
      if (current.forwardingMode() != next.forwardingMode()) restart.add("forwarding.mode");
      if (!Objects.equals(current.forwardingSecretFile(), next.forwardingSecretFile())) restart.add("forwarding.secret-file");
      if (current.authentication().mode() != next.authentication().mode()) restart.add("authentication.mode");
      if (!sameBackends(current.backends(), next.backends())) restart.add("servers.*");
      if (!current.initialBackends().equals(next.initialBackends()) || !current.fallbackBackends().equals(next.fallbackBackends())) {
        restart.add("routing.initial / routing.fallback");
      }
      if (!current.ops().metrics().equals(next.ops().metrics())) restart.add("metrics.prometheus-address");
      List<String> live = new ArrayList<>();
      health.applySettings(next.health());
      live.add("health.*");
      maintenance.applySettings(next.maintenance());
      live.add("maintenance.*");
      versionGate.applySettings(next.versions());
      live.add("versions.*");
      gracefulShutdown.applySettings(next.shutdown());
      live.add("shutdown.*");
      security.applySettings(next.security());
      live.add("security.*");
      modded.applySettings(next.modded());
      live.add("modded.*");
      // Read from the configuration on every ping, so replacing it below is all it takes.
      live.add("status.*");
      // Read from the configuration at every login, the same way.
      live.add("forced-hosts.*");
      this.configuration = current.withOps(next.ops());
      if (!restart.isEmpty()) {
        return new ReloadResult(true, restart, live, null);
      }
      this.configuration = next;
      return new ReloadResult(true, List.of(), live, null);
    } catch (Exception exception) {
      return new ReloadResult(false, List.of(), List.of(), exception.getMessage());
    }
  }

  private static boolean sameBackends(List<BackendServer> a, List<BackendServer> b) {
    if (a.size() != b.size()) return false;
    for (int i = 0; i < a.size(); i++) {
      if (!a.get(i).name().equals(b.get(i).name())) return false;
      if (!a.get(i).address().equals(b.get(i).address())) return false;
      if (!a.get(i).supportedModLoaders().equals(b.get(i).supportedModLoaders())) return false;
    }
    return true;
  }

  /** ProxyPreShutdownEvent goes between the last accept and the first player is disconnected. */
  public void shutdownGracefully(Runnable stopAccepting) {
    gracefulShutdown.run(() -> {
      stopAccepting.run();
      if (started.get()) events.fire(new gg.tame.conduit.api.event.proxy.ProxyPreShutdownEvent(this));
    }, players);
  }

  /**
   * Fires ProxyShutdownEvent and disables every plugin, once. The event used to fire from the
   * serving thread's exit while close() disabled plugins on the closing thread, so a plugin was
   * often gone before it heard the proxy was stopping.
   */
  @Override public void close() {
    if (!closed.compareAndSet(false, true)) return;
    if (started.get()) events.fire(new gg.tame.conduit.api.event.proxy.ProxyShutdownEvent(this));
    plugins.disableAll();
    plugins.closeLoaders();
    health.close();
    scheduler.close();
    synchronized (connectionSelectorLock) {
      if (connectionSelector != null) connectionSelector.close();
    }
    if (metricsEndpoint != null) metricsEndpoint.close();
    // Via is deliberately not stopped here. Its manager is a per-JVM singleton that cannot be
    // re-initialised, so a runtime closing would take translation away from every later one in the
    // same process. Stopping it belongs to the process, and ConduitViaBootstrap owns that as a
    // shutdown hook -- which the launcher's explicit exit is what finally reaches.
  }

  private static final class PlayerViews implements PlayerLookup {
    private final PlayerManager players;
    private PlayerViews(PlayerManager players) { this.players = players; }
    @Override public Optional<Player> get(UUID uniqueId) {
      return players.get(uniqueId).filter(Player.class::isInstance).map(Player.class::cast);
    }
    @Override public Optional<Player> getByUsername(String username) {
      return players.getByUsername(username).filter(Player.class::isInstance).map(Player.class::cast);
    }
    @Override public Collection<Player> all() {
      List<Player> result = new ArrayList<>();
      for (TrackedPlayer player : players.all()) if (player instanceof Player api) result.add(api);
      return List.copyOf(result);
    }
  }
  static final class ServerViews implements ServerManager {
    private final ConduitRuntime runtime;
    private final ServerRegistry registry;
    private final BackendSelector selector;
    private final ConcurrentHashMap<String, ApiServer> views = new ConcurrentHashMap<>();
    ServerViews(ConduitRuntime runtime, ServerRegistry registry, BackendSelector selector) {
      this.runtime = runtime; this.registry = registry; this.selector = selector;
      for (BackendServer server : registry.all()) views.put(ServerRegistry.normalize(server.name()), new ApiServer(server, selector, runtime));
    }
    @Override public List<RegisteredServer> initialServers() { return resolve(runtime.configuration().initialBackends()); }
    @Override public List<RegisteredServer> fallbackServers() { return resolve(runtime.configuration().fallbackBackends()); }
    private List<RegisteredServer> resolve(List<String> names) {
      List<RegisteredServer> result = new ArrayList<>();
      for (String name : names) getServer(name).ifPresent(result::add);
      return List.copyOf(result);
    }
    @Override public Optional<RegisteredServer> getServer(String name) {
      return Optional.ofNullable(views.get(ServerRegistry.normalize(name)));
    }
    @Override public Collection<RegisteredServer> getServers() { return List.copyOf(views.values()); }
    @Override public RegisteredServer register(String name, InetSocketAddress address) {
      BackendServer server = registry.register(new BackendServer(name, address));
      ApiServer view = new ApiServer(server, selector, runtime);
      views.put(ServerRegistry.normalize(name), view);
      runtime.events().fire(new gg.tame.conduit.api.event.proxy.ServerRegisteredEvent(view));
      return view;
    }
    @Override public boolean unregister(String name) {
      ApiServer removed = views.remove(ServerRegistry.normalize(name));
      boolean unregistered = selector.unregister(name);
      if (removed != null) runtime.events().fire(new gg.tame.conduit.api.event.proxy.ServerUnregisteredEvent(removed));
      return unregistered;
    }
  }
  static final class ApiServer implements RegisteredServer {
    private final BackendServer server;
    private final BackendSelector selector;
    private final ConduitRuntime runtime;
    ApiServer(BackendServer server, BackendSelector selector, ConduitRuntime runtime) {
      this.server = server; this.selector = selector; this.runtime = runtime;
    }
    @Override public Collection<Player> players() {
      List<Player> result = new ArrayList<>();
      for (TrackedPlayer player : runtime.playerManager().all()) {
        if (player instanceof Player api && server.name().equalsIgnoreCase(player.currentBackend())) result.add(api);
      }
      return List.copyOf(result);
    }
    @Override public String getName() { return server.name(); }
    @Override public InetSocketAddress getAddress() { return server.address(); }
    @Override public boolean isOnline() { return selector.advertisement(server.name()).isPresent(); }
    @Override public boolean isDraining() {
      return selector.health() != null && selector.health().isDraining(server.name());
    }
    @Override public gg.tame.conduit.api.server.ServerStatus status() { return selector.status(server.name()); }
    @Override public CompletableFuture<gg.tame.conduit.api.server.ServerStatus> ping() {
      return ping(gg.tame.conduit.protocol.BackendStatusProbe.ANY_PROTOCOL, null, null);
    }
    /** On the probe's bounded pool of socket threads. The cache is left alone. */
    @Override public CompletableFuture<gg.tame.conduit.api.server.ServerStatus> ping(int protocol, String virtualHost, java.time.Duration timeout) {
      int millis = timeout == null || timeout.isZero() || timeout.isNegative()
          ? runtime.configuration().health().timeoutMs() : (int) Math.min(Integer.MAX_VALUE, timeout.toMillis());
      return gg.tame.conduit.protocol.BackendStatusProbe.ping(server.name(), server.address(), virtualHost, protocol, millis);
    }
    @Override public CompletableFuture<Boolean> connect(Player player) { return player.connect(this); }
    BackendServer backend() { return server; }
  }
}
