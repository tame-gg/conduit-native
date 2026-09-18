package gg.tame.conduit.api.event.player;

import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.player.Player;

/**
 * A player whose {@link PlayerLoginEvent} allowed them in has left, for any reason. They are no
 * longer in the player lookup. Fired once, on the connection's thread.
 *
 * <p>{@code completedLogin} is whether {@link PlayerPostLoginEvent} fired for them. It is false
 * when the login ended before any backend took the player: no server would have them, or they
 * left or were kicked while being connected. A denied login gets no disconnect event.
 */
public record PlayerDisconnectEvent(Player player, boolean completedLogin) implements Event {
  /** A player who got {@link PlayerPostLoginEvent}. */
  public PlayerDisconnectEvent(Player player) { this(player, true); }
}
