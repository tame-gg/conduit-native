// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.player;

import gg.tame.conduit.api.command.CommandSource;
import gg.tame.conduit.api.server.RegisteredServer;
import gg.tame.conduit.api.text.Text;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * A connected player. Every method may be called from any thread. Messages and plugin messages
 * sent before the player reaches Play are dropped rather than written in the wrong state.
 */
public interface Player extends CommandSource {
  UUID uniqueId();
  String username();
  boolean authenticated();
  String connectionState();
  OptionalServer currentServer();
  /** Protocol number the client joined with, e.g. 47 for 1.8.9 or 765 for 1.20.4. */
  int protocolVersion();
  /**
   * Where the player connected from: the socket's peer, or the forwarded address when one is
   * configured. An address without a port, because a forwarded one has none.
   */
  InetAddress remoteAddress();
  /** Host and port the client says it dialled, from its handshake, unresolved. Forge markers are removed. */
  InetSocketAddress virtualHost();
  /**
   * The language the client says it uses (its "en_us" as {@code en-US}), from the settings it sends
   * once it is in the game and whenever the player changes them. Empty until it has sent them.
   */
  default java.util.Optional<java.util.Locale> locale() { return java.util.Optional.empty(); }
  /**
   * The player's latency in milliseconds: the round trip of the last keep-alive the client answered,
   * as the game itself measures it. -1 until the client has answered one, which in practice takes
   * up to a keep-alive interval (about 15 seconds on a vanilla backend) after it joins.
   */
  default long ping() { return -1; }
  /** What the client last called itself on the brand channel ("vanilla", "fabric", ...). Empty until it has. */
  default java.util.Optional<String> clientBrand() { return java.util.Optional.empty(); }
  /**
   * Offers the client {@code pack}, as the proxy's own: its answers arrive as
   * {@code PlayerResourcePackStatusEvent} and never reach the server. It goes once the client stands
   * in a world, so an offer made while it logs in or switches servers waits for that. The proxy's
   * packs stay across server switches. False when the client's release has no resource-pack packet.
   */
  default boolean sendResourcePack(ResourcePack pack) { return false; }
  /**
   * Tells a 1.20.3+ client to drop the pack with this id, the proxy's or a server's. False for an older
   * client, which has no way to drop one.
   */
  default boolean removeResourcePack(UUID id) { return false; }
  /** Tells a 1.20.3+ client to drop every pack it has from the server side, the proxy's and the server's. False before 1.20.3. */
  default boolean clearResourcePacks() { return false; }
  /** The packs this client was offered through the proxy and still has or is still deciding on, in the order offered. */
  default java.util.List<ResourcePack.Offered> resourcePacks() { return java.util.List.of(); }
  void sendMessage(String message);
  default void sendMessage(Text text) {
    sendMessage(text == null ? "" : text.plain());
  }
  /**
   * Moves the player to {@code server} without telling them anything. Completes true when they
   * arrived. Use {@link #connectWithResult} to learn why they did not.
   */
  CompletableFuture<Boolean> connect(RegisteredServer server);
  /**
   * Moves the player to {@code server}, firing {@code PlayerServerConnectEvent} first. Never
   * completes exceptionally, and never on the calling thread's time: the switch runs on its own
   * thread, so it is safe to call from a listener or a command.
   */
  CompletableFuture<ConnectResult> connectWithResult(RegisteredServer server);
  /** Kicks the player with {@code reason} on their disconnect screen, in whatever state they are in. */
  void disconnect(String reason);
  default void disconnect(Text reason) {
    disconnect(reason == null ? "" : reason.plain());
  }
  /** Sends a plugin message to the player's client. */
  void sendPluginMessage(String channel, byte[] data);
  /**
   * Sends a plugin message to the backend the player is on, as if the client had sent it. False
   * when there is no backend or it is not in a state that takes one.
   */
  boolean sendPluginMessageToServer(String channel, byte[] data);

  // What the proxy itself shows the player. Each is written in the client's own protocol, on top of
  // whatever the backend shows. Anything sent while the client has no world to show it in (before its
  // first Join Game, or while a switch has it back in Configuration) is held and sent once it does.

  /**
   * Shows {@code message} above the hotbar. A 1.8 client gets it as a game-info chat line, with
   * its formatting as section codes because that is all such a line keeps; a 1.7 client has no
   * action bar and is sent nothing.
   */
  void sendActionBar(Text message);
  /**
   * Shows {@code title} now, with the last subtitle and times this client was sent. A 1.7 client has
   * no titles and is sent nothing by any of the title methods.
   */
  void sendTitle(Text title);
  /** Sets the subtitle shown under the next {@link #sendTitle title}; on its own it shows nothing. */
  void sendSubtitle(Text subtitle);
  /** Sets how the next and current titles fade in, stay and fade out. */
  void sendTitleTimes(TitleTimes times);
  /**
   * Shows a title and subtitle together. {@code times} null keeps the client's current times. Sends
   * the times, then the subtitle, then the title, which is the order the client needs to show all
   * three at once.
   */
  default void showTitle(Text title, Text subtitle, TitleTimes times) {
    if (times != null) sendTitleTimes(times);
    sendSubtitle(subtitle);
    sendTitle(title);
  }
  /** Hides the title being shown; the times the client was given stay. */
  void clearTitle();
  /** Hides the title and puts the client's subtitle and times back to its defaults. */
  void resetTitle();
  /** Shows {@code bar}, across server switches, until {@link #hideBossBar} or the player disconnects. */
  void showBossBar(BossBar bar);
  void hideBossBar(BossBar bar);
  /**
   * Sets the text above and below the tab list. The proxy's pair is sent again after every server
   * switch; a backend that sends its own replaces it on the client until then, last writer winning.
   * Both empty clears it and stops the proxy re-sending one. A 1.7 client has no header or footer.
   */
  void sendPlayerListHeaderAndFooter(Text header, Text footer);
  /** The header the proxy last set with {@link #sendPlayerListHeaderAndFooter}, empty if none. */
  Text playerListHeader();
  /** The footer the proxy last set with {@link #sendPlayerListHeaderAndFooter}, empty if none. */
  Text playerListFooter();
  /**
   * Puts {@code entry} on this player's tab list, or updates the proxy's entry with the same id. The
   * proxy's entries stay across server switches and are sent again where the client dropped them; the
   * backend's entries are never touched. Sent to 1.8 and newer clients except 1.20.1, which Conduit
   * has no Player Info table for yet; for 1.7 and 1.20.1 the entry is only remembered.
   */
  void addTabListEntry(TabListEntry entry);
  /** Takes the proxy's entry with this id off the tab list; false when the proxy has none. */
  boolean removeTabListEntry(UUID id);
  /** The proxy's own entries on this player's tab list, in the order they were added. */
  List<TabListEntry> tabListEntries();

  interface OptionalServer {
    boolean isPresent();
    RegisteredServer orElse(RegisteredServer fallback);
    String name();
  }
}
