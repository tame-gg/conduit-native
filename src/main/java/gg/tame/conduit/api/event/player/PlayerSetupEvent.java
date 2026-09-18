// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event.player;

import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.player.Player;

/**
 * A client has logged in to the proxy (and, in online mode, authenticated) and is now a
 * {@link Player}, before anything has decided whether it may join. This is where a plugin sets up
 * what it keeps per player, and a permission plugin loads the player's permissions, so that the
 * decisions that follow see them: maintenance mode's bypass permission, then
 * {@link PlayerLoginEvent}.
 *
 * <p>Everything the proxy refuses a connection for on its own account has already happened: the bot
 * filter, connection throttling, a malformed or unsupported handshake, the version gate, a failed
 * online-mode authentication. A client refused there never becomes a Player, and no plugin hears of
 * it. From this event on, every player gets exactly one {@link PlayerDisconnectEvent}, however the
 * login ends, so anything set up here can be released there.
 *
 * <p>There is nothing to deny here; refusing a player is {@link PlayerLoginEvent}'s. Fired on the
 * connection's own thread, which holds the login until every listener returns. The player is not in
 * the player lookup yet and cannot be sent messages.
 */
public record PlayerSetupEvent(Player player) implements Event {}
