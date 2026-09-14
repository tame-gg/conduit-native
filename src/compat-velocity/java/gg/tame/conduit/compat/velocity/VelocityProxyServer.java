package gg.tame.conduit.compat.velocity;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.config.ProxyConfig;
import com.velocitypowered.api.proxy.messages.ChannelRegistrar;
import com.velocitypowered.api.proxy.player.ResourcePackInfo;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.scheduler.Scheduler;
import com.velocitypowered.api.util.ProxyVersion;
import gg.tame.conduit.Conduit;
import gg.tame.conduit.runtime.ConduitRuntime;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;

final class VelocityProxyServer implements ProxyServer {
  private final ConduitRuntime runtime;
  private final VelocityEnvironment environment;
  private final VelocityConsole console = new VelocityConsole();
  private final VelocityChannelRegistrar channels = new VelocityChannelRegistrar();
  private final VelocityProxyConfig config;
  VelocityProxyServer(ConduitRuntime runtime, VelocityEnvironment environment) {
    this.runtime = runtime;
    this.environment = environment;
    this.config = new VelocityProxyConfig(runtime);
  }
  @Override public void shutdown(Component reason) { shutdown(); }
  @Override public void shutdown() { runtime.close(); }
  @Override public boolean isShuttingDown() { return false; }
  @Override public void closeListeners() { }
  @Override public Optional<Player> getPlayer(String username) {
    return runtime.player(username).map(environment::wrap);
  }
  @Override public Optional<Player> getPlayer(UUID uniqueId) {
    return runtime.player(uniqueId).map(environment::wrap);
  }
  @Override public Collection<Player> getAllPlayers() {
    List<Player> players = new ArrayList<>();
    for (var player : runtime.players().all()) players.add(environment.wrap(player));
    return List.copyOf(players);
  }
  @Override public int getPlayerCount() { return getAllPlayers().size(); }
  @Override public Optional<RegisteredServer> getServer(String name) {
    return runtime.servers().getServer(name).map(environment::wrapServer);
  }
  @Override public Collection<RegisteredServer> getAllServers() {
    List<RegisteredServer> servers = new ArrayList<>();
    for (var server : runtime.servers().getServers()) servers.add(environment.wrapServer(server));
    return List.copyOf(servers);
  }
  @Override public Collection<Player> matchPlayer(String partialName) {
    String needle = partialName.toLowerCase(Locale.ROOT);
    List<Player> matches = new ArrayList<>();
    for (Player player : getAllPlayers()) if (player.getUsername().toLowerCase(Locale.ROOT).startsWith(needle)) matches.add(player);
    return matches;
  }
  @Override public Collection<RegisteredServer> matchServer(String partialName) {
    String needle = partialName.toLowerCase(Locale.ROOT);
    List<RegisteredServer> matches = new ArrayList<>();
    for (RegisteredServer server : getAllServers()) {
      if (server.getServerInfo().getName().toLowerCase(Locale.ROOT).startsWith(needle)) matches.add(server);
    }
    return matches;
  }
  @Override public RegisteredServer createRawRegisteredServer(ServerInfo server) {
    return environment.wrapServer(new gg.tame.conduit.api.server.RegisteredServer() {
      @Override public String getName() { return server.getName(); }
      @Override public InetSocketAddress getAddress() { return server.getAddress(); }
      @Override public boolean isOnline() { return false; }
      @Override public java.util.concurrent.CompletableFuture<Boolean> connect(gg.tame.conduit.api.player.Player player) {
        return player.connect(this);
      }
    });
  }
  @Override public RegisteredServer registerServer(ServerInfo server) {
    return environment.wrapServer(runtime.servers().register(server.getName(), server.getAddress()));
  }
  @Override public void unregisterServer(ServerInfo server) {
    runtime.servers().unregister(server.getName());
  }
  @Override public ConsoleCommandSource getConsoleCommandSource() { return console; }
  @Override public PluginManager getPluginManager() { return environment.plugins(); }
  @Override public EventManager getEventManager() { return environment.events(); }
  @Override public CommandManager getCommandManager() { return environment.commands(); }
  @Override public Scheduler getScheduler() { return environment.scheduler(); }
  @Override public ChannelRegistrar getChannelRegistrar() { return channels; }
  @Override public InetSocketAddress getBoundAddress() { return new InetSocketAddress("127.0.0.1", 25565); }
  @Override public ProxyConfig getConfiguration() { return config; }
  @Override public ProxyVersion getVersion() { return new ProxyVersion("Conduit", "tame.gg", Conduit.VERSION); }
  @Override public ResourcePackInfo.Builder createResourcePackBuilder(String url) {
    return UnsupportedApis.unsupported("ProxyServer.createResourcePackBuilder");
  }
}
