// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event.player;

import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.api.player.ResourcePack;

/**
 * The client answered about a resource pack it was offered through the proxy. {@code fromServer} says
 * whose pack it is: an answer about the proxy's own pack ends here, and one about a server's pack
 * still goes on to that server, which is waiting for it. A 1.20.3+ client names the pack it means; for
 * an older one the answer belongs to the earliest offer it has not finished answering. Fired on the
 * player's connection thread, which waits for every listener: do not block.
 */
public record PlayerResourcePackStatusEvent(Player player, ResourcePack pack, boolean fromServer, ResourcePack.Status status) implements Event {}
