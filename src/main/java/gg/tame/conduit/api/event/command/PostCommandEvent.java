// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event.command;

import gg.tame.conduit.api.command.CommandSource;
import gg.tame.conduit.api.event.Event;

/**
 * The proxy is done with a command a player, the console or a plugin ran. {@link #command()} is the
 * command line without its leading slash.
 * <ul>
 *   <li>{@link Result#EXECUTED}: a proxy command's handler returned, or the source was told it may not
 *       use the command. A handler that hands its work to another thread is done, here, once it has
 *       handed it over.
 *   <li>{@link Result#EXCEPTION}: the handler threw.
 *   <li>{@link Result#FORWARDED}: a player's command went on to their backend, because the proxy has
 *       no such command for them or a {@link CommandExecuteEvent} listener sent it on.
 * </ul>
 * Not fired for a command a {@link CommandExecuteEvent} listener cancelled, nor for a line the proxy
 * has no command for when no backend is there to take it. Fired on the thread that ran the command,
 * a player's connection thread for a player's command: do not block.
 */
public record PostCommandEvent(CommandSource source, String command, Result result) implements Event {
  public enum Result { EXECUTED, FORWARDED, EXCEPTION }
}
