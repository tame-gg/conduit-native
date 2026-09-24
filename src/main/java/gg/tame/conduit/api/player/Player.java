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
  /** {@link #remoteAddress()} with the port the client connected from; 0 for a forwarded address, which has none. */
  default InetSocketAddress remoteSocketAddress() { return new InetSocketAddress(remoteAddress(), 0); }
  /** Host and port the client says it dialled, from its handshake, unresolved. Forge markers are removed. */
  InetSocketAddress virtualHost();
  /**
   * The profile backends are told, read-only: {@link #uniqueId()}, {@link #username()} and the
   * properties (skin textures) the session server gave an online-mode player, or what a
   * {@code GameProfileRequestEvent} listener replaced them with. An offline player has none.
   */
  default GameProfile gameProfile() { return new GameProfile(uniqueId(), username(), java.util.List.of()); }
  /**
   * Replaces the properties of {@link #gameProfile()}. Backends hear of them through forwarding on the
   * player's next connection to one; the backend the player is on now keeps what it was told.
   *
   * @return false when this player cannot take new properties, which this default cannot
   */
  default boolean setGameProfileProperties(List<GameProfile.Property> properties) { return false; }
  /** Whether the client arrived by a Transfer packet from another server (a 1.20.5+ handshake with intent 3). */
  default boolean transferred() { return false; }
  /**
   * Sends the client to another address with a Transfer packet: it leaves this proxy and connects to
   * {@code host}:{@code port} itself, arriving there by transfer. Not a move between this proxy's own
   * servers. {@code PlayerTransferEvent} is fired first and may cancel it or change the address.
   *
   * @return false, with nothing sent, when the client's version has no Transfer packet (before
   *     1.20.5), when it is not in the Configuration or Play phase, or when a listener cancelled it
   * @throws IllegalArgumentException for a blank host or a port outside 1 to 65535
   */
  default boolean transferToHost(String host, int port) { return false; }
  /**
   * The language the client says it uses (its "en_us" as {@code en-US}), from the settings it sends
   * once it is in the game and whenever the player changes them. Empty until it has sent them.
   */
  default java.util.Optional<java.util.Locale> locale() { return settings().map(ClientSettings::locale); }
  /** Everything the client last said about itself in its settings; empty until it has sent them. */
  default java.util.Optional<ClientSettings> settings() { return java.util.Optional.empty(); }
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
   * in a world, so an offer made while it logs in or switches servers waits for that, unless plugins
   * hold a 1.20.2+ client in its Configuration phase ({@code PlayerConfigurationEvent}), when it goes
   * at once. The proxy's packs stay across server switches. False when the client's release has no
   * resource-pack packet.
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
   * backend's entries are left alone. A 1.7 client, whose list names entries by text, lists it under
   * its display name (as legacy text, else its name, cut to 16 characters) with its latency only.
   */
  void addTabListEntry(TabListEntry entry);
  /** Takes the proxy's entry with this id off the tab list; false when the proxy has none. */
  boolean removeTabListEntry(UUID id);
  /** The proxy's own entries on this player's tab list, in the order they were added. */
  List<TabListEntry> tabListEntries();
  /**
   * The backend's entries on this player's tab list, as its Player Info packets have told the client
   * since its Join Game, which starts them afresh on each server. A 1.7 entry has no UUID and is named
   * by the offline-mode UUID of the text it is listed under.
   */
  List<TabListEntry> backendTabListEntries();
  /**
   * Shows the backend's entry with {@code entry}'s id on the client as {@code entry} says, profile
   * aside, which stays the backend's; the backend's own next update of a field replaces it. A 1.7
   * client is sent the latency only. False when the backend has no entry with that id.
   */
  boolean updateBackendTabListEntry(TabListEntry entry);
  /** Takes the backend's entry with this id off the client's tab list; false when the backend has none. */
  boolean removeBackendTabListEntry(UUID id);

  // Sounds, by name. A sound is sent only while the client stands in a world, and is dropped rather
  // than held when it does not (before its first Join Game, or while a switch reconfigures it).

  /**
   * Plays {@code sound} at the player, following them as they move. Only 1.19.3+ clients can be sent
   * that by name; older ones are sent nothing, since Conduit does not know where the player stands.
   */
  void playSound(Sound sound);
  /** Plays {@code sound} at a position in the player's current world. Every client from 1.7. */
  void playSound(Sound sound, double x, double y, double z);
  /**
   * Plays {@code sound} following {@code emitter}, another player (or this one) on the same backend,
   * by the entity id that backend gave them. Only 1.19.3+ clients, as for {@link #playSound(Sound)};
   * nothing is sent when the emitter is on another backend or has not joined one yet. This default
   * sends nothing.
   */
  default void playSound(Sound sound, Player emitter) { }
  /**
   * Stops sounds the client is playing: those named {@code name} (null for any) in {@code source}
   * (null for every source). From 1.9.3 clients; 1.7 and 1.8 have no way to stop one and are sent nothing.
   */
  void stopSound(String name, Sound.Source source);

  // Speaking for the player, and what the proxy keeps on their client. Each is sent at once, in the
  // client's own protocol, and only once the player has joined; nothing is held back or sent again
  // after a server switch.

  /**
   * Sends {@code input} to the player's backend as though they had typed it into chat: a line starting
   * with {@code /} as a command, anything else as a chat message. Only the backend hears it; the proxy's
   * own commands and its chat and command events do not.
   *
   * <p>A 1.19+ client signs what it says, and the proxy cannot sign for it: such a client is sent as a
   * client with chat signing off would send it, unsigned. Its account of the signed chat it has seen is
   * the one the backend already holds, so the client's own next message still agrees with the
   * backend's. A backend with {@code enforce-secure-profile=true} refuses unsigned chat from a player
   * who has a chat key, and may disconnect them over it. From 1.20.5 a command goes as the unsigned
   * command alone, which a backend refuses for a command whose arguments it expects signed, such as
   * {@code /msg} or {@code /me}.
   *
   * @return false, with nothing sent, when the player is not in Play on a backend
   * @throws IllegalArgumentException for input longer than the client's chat box takes: 256 characters, 100 before 1.11
   */
  default boolean spoofChatInput(String input) { return false; }
  /**
   * The chat key the client sent: a 1.19 to 1.19.2 client's in its Login Start, a 1.19.3+ client's in
   * the game once it is there. Empty for an older client, one with chat signing off, and every
   * client of an offline-mode network, whose clients have no key to send.
   */
  default java.util.Optional<ChatSession> chatSession() { return java.util.Optional.empty(); }
  /**
   * Shows the signed chat message with {@code signature} as deleted on a 1.19.1+ client, as a server's
   * Delete Chat does; one the client does not have is ignored. Nothing about the message's chain changes.
   *
   * @return false, with nothing sent, for an older client or one not in Play
   * @throws IllegalArgumentException for a 1.19.3+ client and a signature that is not 256 bytes
   */
  default boolean deleteChatMessage(byte[] signature) { return false; }
  /** What {@link #updateCustomChatCompletions} does with the strings it is given. */
  enum ChatCompletions { ADD, REMOVE, SET }
  /**
   * Adds, removes or replaces the extra words a 1.19.1+ client offers when the player presses Tab while
   * typing chat. The proxy does not keep them.
   *
   * @return false, with nothing sent, for an older client or one not in Play
   */
  default boolean updateCustomChatCompletions(ChatCompletions action, java.util.Collection<String> completions) { return false; }
  /**
   * Replaces the links in a 1.21+ client's pause menu. A backend that sends its own replaces these.
   *
   * @return false, with nothing sent, for an older client or one neither configuring nor in Play
   */
  default boolean setServerLinks(List<ServerLink> links) { return false; }
  /**
   * Closes the dialog the client is showing, whoever opened it. Sent to 26.2 and later clients, the
   * dialog releases whose packet ids Conduit's tables carry; 1.21.6 to 26.1 have dialogs too but are
   * sent nothing.
   *
   * @return false, with nothing sent, for any other client or one neither configuring nor in Play
   */
  default boolean closeDialog() { return false; }
  /**
   * Stores {@code data} on a 1.20.5+ client under {@code key}, a namespaced key such as
   * {@code myplugin:token} ({@code minecraft:} when it has no namespace). The client keeps it when it is
   * transferred to another server and forgets it when it disconnects.
   *
   * @return false, with nothing sent, for an older client or one neither configuring nor in Play
   * @throws IllegalArgumentException for a key that is not a namespaced key, or more than 5 KiB (5120 bytes) of data
   */
  default boolean storeCookie(String key, byte[] data) { return false; }
  /**
   * Asks a 1.20.5+ client for its cookie under {@code key}. The answer arrives as
   * {@code PlayerCookieReceiveEvent} and never reaches the backend.
   *
   * @return false, with nothing sent, for an older client or one neither configuring nor in Play
   * @throws IllegalArgumentException for a key that is not a namespaced key
   */
  default boolean requestCookie(String key) { return false; }

  interface OptionalServer {
    boolean isPresent();
    RegisteredServer orElse(RegisteredServer fallback);
    String name();
  }
}
