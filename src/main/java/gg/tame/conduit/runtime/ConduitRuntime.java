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
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.event.ConduitEventManager;
import gg.tame.conduit.permission.PermissivePermissionProvider;
import gg.tame.conduit.plugin.ConduitPluginManager;
import gg.tame.conduit.routing.BackendSelector;
import gg.tame.conduit.routing.ServerRegistry;
import gg.tame.conduit.scheduler.ConduitScheduler;
import gg.tame.conduit.session.PlayerManager;
import gg.tame.conduit.session.TrackedPlayer;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class ConduitRuntime implements ConduitProxy, AutoCloseable {
  private final CommandManager commands;
  private final PlayerManager players;
  private final BackendSelector selector;
  private final ConduitEventManager events = new ConduitEventManager();
  private final ConduitScheduler scheduler = new ConduitScheduler();
  private final PermissionProvider permissions = new PermissivePermissionProvider();
  private final ServerViews servers;
  private final PlayerViews playerViews;
  private final ConduitPluginManager plugins;
  public ConduitRuntime(ConduitConfiguration configuration, Path pluginsDirectory) {
    this.selector = new BackendSelector(configuration);
    this.commands = new CommandManager();
    this.players = new PlayerManager();
    this.servers = new ServerViews(selector.registry(), selector);
    this.playerViews = new PlayerViews(players);
    this.plugins = new ConduitPluginManager(pluginsDirectory, this, events, scheduler, commands);
  }
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
  @Override public void close() {
    plugins.disableAll();
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
      List<Player> result = new java.util.ArrayList<>();
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
    @Override public CompletableFuture<Boolean> connect(Player player) { return player.connect(this); }
    BackendServer backend() { return server; }
  }
}
