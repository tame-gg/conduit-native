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
  /**
   * The permission provider in force. Until a plugin sets one, the default grants a player no
   * {@code conduit.} node and every other node, and lets nobody through maintenance mode (see
   * {@link PermissionProvider#manages}).
   */
  PermissionProvider permissions();
  /**
   * Makes {@code provider} answer every permission check, for players and commands alike, until
   * {@code owner} is disabled; then the default comes back. The last plugin to call this wins. A
   * provider that throws denies the permission it was asked about. Called on whichever thread is
   * checking, often a player's connection thread, so it must answer from memory. A joining player is
   * first asked about after {@code PlayerSetupEvent}, which is where a provider loads them.
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
   * Packets this many bytes or larger are compressed on the way to players; -1 when the link to
   * players is not compressed. This default says it is not.
   */
  default int compressionThreshold() { return -1; }
  /**
   * Stops the proxy the way the operator would: players are moved or kicked as configured, then
   * {@code ProxyShutdownEvent} fires and every plugin is disabled. Returns at once; the shutdown
   * runs on its own thread, so a plugin may call this from anywhere, including its own listener.
   */
  void shutdown();
  /**
   * {@link #shutdown()}, with every player kicked with {@code reason} instead of the configured
   * shutdown message. This default ignores the reason.
   */
  default void shutdown(Text reason) { shutdown(); }
  boolean shuttingDown();
  /**
   * Stops taking new connections, the game listener's and the query port's, and leaves everyone
   * already connected where they are. There is no reopening them; {@link #shutdown()} still ends the
   * proxy as usual. This default does nothing.
   */
  default void closeListeners() { }
  /**
   * Tells every backend a player joins that the proxy listens on this plugin channel, and tells the
   * backends players are on now. A Paper or Spigot backend sends a plugin message only on a channel
   * the connection registered, so a plugin that wants to hear its backend half on a channel of its
   * own calls this first; without it the backend drops the message and {@code PluginMessageEvent}
   * never fires. This default does nothing.
   */
  default void listenOnChannel(String channel) { }
  /** Undoes {@link #listenOnChannel} for backends joined from now on. This default does nothing. */
  default void stopListeningOnChannel(String channel) { }
  /**
   * The server-list answer as the operator configured it ({@code [status]} in conduit.toml), for a
   * plugin that wants to start from it. What a given client is actually sent can differ: see
   * {@code ServerListPingEvent}. This default answers with Conduit's built-in defaults.
   */
  default ServerListDefaults serverListDefaults() {
    return new ServerListDefaults(Text.of("Conduit"), 100, Optional.empty());
  }
  /**
   * {@code [forced-hosts]} as configured: each hostname, lower-cased without a trailing dot, to the
   * server names tried for it in order. This default has none.
   */
  default java.util.Map<String, java.util.List<String>> forcedHosts() { return java.util.Map.of(); }
  /** The UDP port the GameSpy 4 query ({@code [query]}) answers on, empty when it is off. This default has none. */
  default java.util.OptionalInt queryPort() { return java.util.OptionalInt.empty(); }
}
