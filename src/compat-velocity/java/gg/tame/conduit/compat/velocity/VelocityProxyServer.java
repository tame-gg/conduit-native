// SPDX-License-Identifier: GPL-3.0-or-later
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
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;

/** The ProxyServer handed to Velocity plugins. Its public surface is the Velocity API and nothing else. */
final class VelocityProxyServer implements ProxyServer, Unsupported.PlayerGroup {
  private final VelocityEnvironment environment;
  private final VelocityProxyConfig config;
  VelocityProxyServer(VelocityEnvironment environment) {
    this.environment = environment;
    this.config = new VelocityProxyConfig(environment);
  }

  @Override public Optional<Player> getPlayer(String username) {
    return username == null ? Optional.empty() : environment.conduit.player(username).map(environment::player);
  }
  @Override public Optional<Player> getPlayer(UUID uniqueId) {
    return uniqueId == null ? Optional.empty() : environment.conduit.player(uniqueId).map(environment::player);
  }
  @Override public Collection<Player> getAllPlayers() {
    List<Player> players = new ArrayList<>();
    for (var player : environment.conduit.players().all()) players.add(environment.player(player));
    return List.copyOf(players);
  }
  @Override public int getPlayerCount() { return environment.conduit.players().all().size(); }
  @Override public Collection<Player> matchPlayer(String partialName) {
    String prefix = partialName.toLowerCase(Locale.ROOT);
    return getAllPlayers().stream().filter(player -> player.getUsername().toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
  }

  @Override public Optional<RegisteredServer> getServer(String name) {
    return name == null ? Optional.empty() : environment.conduit.servers().getServer(name).map(environment::server);
  }
  @Override public Collection<RegisteredServer> getAllServers() {
    List<RegisteredServer> servers = new ArrayList<>();
    for (var server : environment.conduit.servers().getServers()) servers.add(environment.server(server));
    return List.copyOf(servers);
  }
  @Override public Collection<RegisteredServer> matchServer(String partialName) {
    String prefix = partialName.toLowerCase(Locale.ROOT);
    return getAllServers().stream().filter(server -> server.getServerInfo().getName().toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
  }
  @Override public RegisteredServer registerServer(ServerInfo server) {
    if (environment.conduit.servers().getServer(server.getName()).isPresent()) {
      throw new IllegalArgumentException("a server named " + server.getName() + " is already registered");
    }
    return environment.server(environment.conduit.servers().register(server.getName(), server.getAddress()));
  }
  @Override public void unregisterServer(ServerInfo server) {
    var registered = environment.conduit.servers().getServer(server.getName())
        .orElseThrow(() -> new IllegalArgumentException("no server named " + server.getName() + " is registered"));
    if (!registered.getAddress().equals(server.getAddress())) {
      throw new IllegalArgumentException("server " + server.getName() + " is registered with a different address");
    }
    environment.conduit.servers().unregister(server.getName());
  }
  /** Pingable, as the contract has it; a connection request to it fails, as Conduit sends players only to servers it has registered. */
  @Override public RegisteredServer createRawRegisteredServer(ServerInfo server) {
    return new VelocityRegisteredServer(environment, environment.conduit.servers().raw(server.getName(), server.getAddress()));
  }

  /** A broadcast: every player, and the console. */
  @Override public void deliver(Component message) {
    for (Player player : getAllPlayers()) player.sendMessage(message);
    environment.console.sendMessage(message);
  }
  /** Every player; not the console, which has no title, action bar or boss bar to show. */
  @Override public Iterable<? extends Audience> audiences() { return getAllPlayers(); }

  @Override public ConsoleCommandSource getConsoleCommandSource() { return environment.console; }
  @Override public PluginManager getPluginManager() { return environment.plugins; }
  @Override public EventManager getEventManager() { return environment.events; }
  @Override public CommandManager getCommandManager() { return environment.commands; }
  @Override public Scheduler getScheduler() { return environment.scheduler; }
  @Override public ChannelRegistrar getChannelRegistrar() { return environment.channels; }
  @Override public ProxyConfig getConfiguration() { return config; }
  @Override public ProxyVersion getVersion() { return new ProxyVersion("Conduit", "tame.gg", environment.conduit.version()); }

  @Override public void shutdown(Component reason) { environment.conduit.shutdown(reason == null ? null : Texts.toConduit(reason)); }
  @Override public void shutdown() { environment.conduit.shutdown(); }
  @Override public boolean isShuttingDown() { return environment.conduit.shuttingDown(); }
  /** The game listener and the query port; players already connected stay. */
  @Override public void closeListeners() { environment.conduit.closeListeners(); }
  @Override public InetSocketAddress getBoundAddress() { return environment.conduit.boundAddress(); }
  @Override public ResourcePackInfo.Builder createResourcePackBuilder(String url) { return new VelocityResourcePackInfo.Builder(url); }
  @Override public String toString() { return "Conduit " + environment.conduit.version(); }
}
