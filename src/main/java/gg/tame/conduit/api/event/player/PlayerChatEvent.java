// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event.player;

import gg.tame.conduit.api.event.Cancellable;
import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.player.Player;

/**
 * A player sent a chat message (not a command: those raise {@code CommandExecuteEvent}).
 * Cancelling drops it before it reaches the backend.
 *
 * <p>Fired only for clients older than 1.19. From 1.19 chat is signed by the client and Conduit
 * relays it untouched, because dropping one message breaks the chain every later one is checked
 * against. Fired on the player's connection thread; do not block.
 */
public final class PlayerChatEvent implements Event, Cancellable {
  private final Player player;
  private final String message;
  private volatile boolean cancelled;
  public PlayerChatEvent(Player player, String message) {
    this.player = player; this.message = message;
  }
  public Player player() { return player; }
  public String message() { return message; }
  @Override public boolean cancelled() { return cancelled; }
  @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
}
