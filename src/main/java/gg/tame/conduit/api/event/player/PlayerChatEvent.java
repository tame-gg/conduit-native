// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event.player;

import gg.tame.conduit.api.event.Cancellable;
import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.player.Player;

/**
 * A player sent a chat message (not a command: those raise {@code CommandExecuteEvent}).
 * Cancelling drops it before it reaches the backend; {@link #setMessage} sends the backend another
 * message in its place.
 *
 * <p>Fired only for clients older than 1.19. From 1.19 chat is signed by the client and Conduit
 * relays it untouched, because dropping or changing one message breaks the chain every later one is
 * checked against. Fired on the player's connection thread; do not block.
 */
public final class PlayerChatEvent implements Event, Cancellable {
  private final Player player;
  private final String original;
  private volatile String message;
  private volatile boolean cancelled;
  public PlayerChatEvent(Player player, String message) {
    this.player = player; this.original = message; this.message = message;
  }
  public Player player() { return player; }
  /** The message as it stands, rewritten or not. */
  public String message() { return message; }
  /** The message as the player typed it. */
  public String originalMessage() { return original; }
  /**
   * What the backend gets instead. A message that would read as a command -- one that starts with a
   * slash -- is refused, since the backend would run it as the player.
   */
  public void setMessage(String message) {
    if (message == null || message.isEmpty() || message.startsWith("/")) {
      throw new IllegalArgumentException("a chat message must be non-empty and not start with '/'");
    }
    this.message = message;
  }
  @Override public boolean cancelled() { return cancelled; }
  @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
}
