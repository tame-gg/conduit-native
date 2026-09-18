// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event.player;

import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.player.Player;
import java.util.List;

/**
 * The backend answered a Tab press from a client older than 1.13, one that has no command tree and
 * asks the server for every completion. {@code partialMessage} is what the player had typed; {@code
 * suggestions} is what the client is about to be sent, and a listener may add, remove or reorder
 * entries in it. The client puts the chosen entry in place of the last word typed, so a command name
 * is suggested with its slash. When a command name is being completed, the proxy's own matching
 * commands are already in the list.
 *
 * <p>Not fired for a Tab press the proxy answers itself (the arguments of a proxy command), nor for a
 * 1.13+ client, whose completions are matched to requests by id. Fired on the thread reading the
 * player's backend, which waits for every listener: do not block.
 */
public record PlayerTabCompleteEvent(Player player, String partialMessage, List<String> suggestions) implements Event {}
