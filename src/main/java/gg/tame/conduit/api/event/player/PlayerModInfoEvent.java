// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event.player;

import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.api.server.ModInfo;

/**
 * A 1.13+ Forge client named its mods, in its answer to its first server's Forge login handshake.
 * Read in passing: the answer goes on to the backend unchanged. Fired once the player has joined,
 * on the thread that logged them in. A 1.7-1.12 client's mod list, sent later in Play, is not
 * reported here.
 */
public record PlayerModInfoEvent(Player player, ModInfo modInfo) implements Event {}
