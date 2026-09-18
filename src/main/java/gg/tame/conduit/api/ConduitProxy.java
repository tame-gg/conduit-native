// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api;

import gg.tame.conduit.api.command.CommandManager;
import gg.tame.conduit.api.command.CommandSource;
import gg.tame.conduit.api.event.EventManager;
import gg.tame.conduit.api.permission.PermissionProvider;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.api.player.PlayerLookup;
import gg.tame.conduit.api.plugin.Plugin;
import gg.tame.conduit.api.plugin.PluginManager;
import gg.tame.conduit.api.scheduler.Scheduler;
import gg.tame.conduit.api.server.ServerListDefaults;
import gg.tame.conduit.api.server.ServerManager;
import gg.tame.conduit.api.text.Text;
import java.net.InetSocketAddress;
import java.util.Optional;

/** Stable native proxy entry point. Does not expose sockets, ciphers, or forwarding secrets. */
public interface ConduitProxy {
  String version();
  int apiVersion();
  PlayerLookup players();
  ServerManager servers();
  CommandManager commands();
  EventManager events();
  PluginManager plugins();
  Scheduler scheduler();
  /** The permission provider in force: the default grants everything, until a plugin sets one. */
  PermissionProvider permissions();
  /**
   * Makes {@code provider} answer every permission check, for players and commands alike, until
   * {@code owner} is disabled; then the default comes back. The last plugin to call this wins. A
   * provider that throws denies the permission it was asked about. Called on whichever thread is
   * checking, often a player's connection thread, so it must answer from memory.
   *
   * @throws IllegalStateException when {@code owner} has been disabled
   */
  void setPermissionProvider(Plugin owner, PermissionProvider provider);
  Optional<Player> player(java.util.UUID uniqueId);
  Optional<Player> player(String username);
  /** The proxy's own terminal, as a command source: every permission, output to standard out. */
  CommandSource console();
  /** Address the proxy accepts players on. */
  InetSocketAddress boundAddress();
  /** Whether players are authenticated with Mojang (online mode) rather than trusted by name. */
  boolean onlineMode();
  /**
   * Stops the proxy the way the operator would: players are moved or kicked as configured, then
   * {@code ProxyShutdownEvent} fires and every plugin is disabled. Returns at once; the shutdown
   * runs on its own thread, so a plugin may call this from anywhere, including its own listener.
   */
  void shutdown();
  boolean shuttingDown();
  /**
   * The server-list answer as the operator configured it ({@code [status]} in conduit.toml), for a
   * plugin that wants to start from it. What a given client is actually sent can differ: see
   * {@code ServerListPingEvent}. This default answers with Conduit's built-in defaults.
   */
  default ServerListDefaults serverListDefaults() {
    return new ServerListDefaults(Text.of("Conduit"), 100, Optional.empty());
  }
}
