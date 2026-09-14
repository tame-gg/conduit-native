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
import gg.tame.conduit.permission.PermissivePermissionProvider;
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
  private final VersionGate versionGate;
  private final GracefulShutdown gracefulShutdown;
  private final SecurityService security;
  private final ModdedService modded;
  private final ConduitEventManager events = new ConduitEventManager();
  private final ConduitScheduler scheduler = new ConduitScheduler();
  private final PermissionProvider permissions = new PermissivePermissionProvider();
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
    this.versionGate = new VersionGate(configuration.versions());
    this.gracefulShutdown = new GracefulShutdown(configuration.shutdown());
    this.security = new SecurityService(configuration.security());
    this.modded = new ModdedService(configuration.modded());
    this.commands = new CommandManager();
    this.players = new PlayerManager();
    this.servers = new ServerViews(selector.registry(), selector);
    this.playerViews = new PlayerViews(players);
    this.plugins = new ConduitPluginManager(pluginsDirectory, this, events, scheduler, commands);
    health.start();
  }

  public void bindConfigPath(Path path) { this.configPath = path; }
  public Path configPath() { return configPath; }
  public Path configDirectory() { return configDirectory; }
  public ConduitConfiguration configuration() { return configuration; }
  public BackendHealthService health() { return health; }
  public MaintenanceService maintenance() { return maintenance; }
  public VersionGate versionGate() { return versionGate; }
  public GracefulShutdown gracefulShutdown() { return gracefulShutdown; }
  public SecurityService security() { return security; }
  public ModdedService modded() { return modded; }
  public PluginCatalog pluginCatalog() { return pluginCatalog; }
  public long uptimeMillis() { return Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000L); }
  public CommandManager commandManager() { return commands; }
  public PlayerManager playerManager() { return players; }
  public BackendSelector selector() { return selector; }
  public ConduitEventManager eventBus() { return events; }
  @Override public String version() { return Conduit.VERSION; }
  @Override public int apiVersion() { return Conduit.API_VERSION; }
  @Override public PlayerLookup players() { return playerViews; }
  @Override public ServerManager servers() { return servers; }
  @Override public CommandManager commands() { return commands; }
  @Override public ConduitEventManager events() { return events; }
  @Override public PluginManager plugins() { return plugins; }
  @Override public Scheduler scheduler() { return scheduler; }
  @Override public PermissionProvider permissions() { return permissions; }
  @Override public Optional<Player> player(UUID uniqueId) { return playerViews.get(uniqueId); }
  @Override public Optional<Player> player(String username) { return playerViews.getByUsername(username); }
  public ConduitPluginManager pluginRuntime() { return plugins; }
  public Optional<RegisteredServer> registered(String name) { return servers.getServer(name); }
  public boolean isMaintenanceActive() { return maintenance.isActive(); }

  public record ReloadResult(boolean applied, List<String> restartRequired, List<String> appliedLive, String error) {
    public ReloadResult {
      restartRequired = List.copyOf(restartRequired);
      appliedLive = List.copyOf(appliedLive);
    }
  }

  public synchronized ReloadResult reload() {
    if (configPath == null) return new ReloadResult(false, List.of(), List.of(), "No config path bound.");
    try {
      ConfigMigrator.migrate(configPath);
      ConduitConfiguration next = ConfigurationLoader.load(configPath);
      List<String> restart = new ArrayList<>();
      ConduitConfiguration current = this.configuration;
      if (!Objects.equals(current.listener(), next.listener())) restart.add("listener.host / listener.port");
      if (current.maxFrameBytes() != next.maxFrameBytes()) restart.add("listener.max-frame-bytes");
      if (current.forwardingMode() != next.forwardingMode()) restart.add("forwarding.mode");
      if (!Objects.equals(current.forwardingSecretFile(), next.forwardingSecretFile())) restart.add("forwarding.secret-file");
      if (current.authentication().mode() != next.authentication().mode()) restart.add("authentication.mode");
      if (!sameBackends(current.backends(), next.backends())) restart.add("servers.*");
      if (!current.initialBackends().equals(next.initialBackends()) || !current.fallbackBackends().equals(next.fallbackBackends())) {
        restart.add("routing.initial / routing.fallback");
      }
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

  public void shutdownGracefully(Runnable stopAccepting) {
    gracefulShutdown.run(stopAccepting, players, selector);
  }

  @Override public void close() {
    plugins.disableAll();
    health.close();
    scheduler.close();
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
    private final ServerRegistry registry;
    private final BackendSelector selector;
    private final ConcurrentHashMap<String, ApiServer> views = new ConcurrentHashMap<>();
    ServerViews(ServerRegistry registry, BackendSelector selector) {
      this.registry = registry; this.selector = selector;
      for (BackendServer server : registry.all()) views.put(ServerRegistry.normalize(server.name()), new ApiServer(server, selector));
    }
    @Override public Optional<RegisteredServer> getServer(String name) {
      return Optional.ofNullable(views.get(ServerRegistry.normalize(name)));
    }
    @Override public Collection<RegisteredServer> getServers() { return List.copyOf(views.values()); }
    @Override public RegisteredServer register(String name, InetSocketAddress address) {
      BackendServer server = registry.register(new BackendServer(name, address));
      ApiServer view = new ApiServer(server, selector);
      views.put(ServerRegistry.normalize(name), view);
      return view;
    }
    @Override public boolean unregister(String name) {
      views.remove(ServerRegistry.normalize(name));
      return registry.unregister(name);
    }
  }
  static final class ApiServer implements RegisteredServer {
    private final BackendServer server;
    private final BackendSelector selector;
    ApiServer(BackendServer server, BackendSelector selector) { this.server = server; this.selector = selector; }
    @Override public String getName() { return server.name(); }
    @Override public InetSocketAddress getAddress() { return server.address(); }
    @Override public boolean isOnline() { return selector.advertisement(server.name()).isPresent(); }
    @Override public boolean isDraining() {
      return selector.health() != null && selector.health().isDraining(server.name());
    }
    @Override public gg.tame.conduit.api.server.ServerStatus status() { return selector.status(server.name()); }
    @Override public CompletableFuture<Boolean> connect(Player player) { return player.connect(this); }
    BackendServer backend() { return server; }
  }
}
