// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event.player;

import gg.tame.conduit.api.command.CommandSyntax;
import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.player.Player;
import java.util.List;
import java.util.Map;

/**
 * A 1.13+ client is about to be sent its command tree: on joining, on each server switch, and again
 * whenever the proxy's commands or permissions change.
 *
 * <p>{@code commands} is every top-level command the tree holds, the backend's first and then the
 * proxy's; removing a name leaves that command out. {@code added} starts empty; a listener puts a
 * name and the shape that follows it to add a command, or to replace one of the same name (an empty
 * shape is declared as one greedy argument, as any command that declares nothing is). The client
 * only parses and completes against this tree: a command left out still runs if typed.
 *
 * <p>The backend's commands are listed only when Conduit can read the backend's tree for the
 * client's release; otherwise they are absent from {@code commands} and always sent. Fired on the
 * thread relaying the backend's tree, which waits for every listener: do not block.
 */
public record PlayerAvailableCommandsEvent(Player player, List<String> commands, Map<String, List<CommandSyntax>> added)
    implements Event {}
