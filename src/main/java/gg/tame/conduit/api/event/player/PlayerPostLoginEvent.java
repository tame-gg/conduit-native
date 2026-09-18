// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event.player;

import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.player.Player;

/**
 * The player is on their first backend and can be found through the player lookup. Every player
 * that gets this event later gets exactly one {@link PlayerDisconnectEvent}. Fired on the
 * connection's thread before any of the player's packets are relayed; do not block.
 */
public record PlayerPostLoginEvent(Player player) implements Event {}
