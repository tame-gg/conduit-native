package gg.tame.conduit.api.event.player;

import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.player.Player;

/**
 * A player who got {@link PlayerPostLoginEvent} has left, for any reason. They are already gone
 * from the player lookup. Fired once, on the connection's thread.
 */
public record PlayerDisconnectEvent(Player player) implements Event {}
