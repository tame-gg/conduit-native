// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event.command;

import gg.tame.conduit.api.command.CommandSource;
import gg.tame.conduit.api.event.Cancellable;
import gg.tame.conduit.api.event.Event;

/**
 * A player typed a command, proxy or backend. Fired before Conduit looks the command up.
 *
 * <p>Cancelling drops it: no proxy command runs and nothing reaches the backend. {@link #command()}
 * has no leading slash. Fired on the player's connection thread; do not block.
 *
 * <p>A listener may {@link #setCommand rewrite} the command, which is then what the proxy looks up
 * and runs, and {@link #forwardToServer send it to the backend} without the proxy looking it up at
 * all. A rewritten command reaches the backend as rewritten only from a client before 1.19; from a
 * later one, whose commands may carry signatures over their arguments, the backend gets the command
 * as the client sent it, and a warning is logged.
 */
public final class CommandExecuteEvent implements Event, Cancellable {
  private final CommandSource source;
  private final String original;
  private volatile String command;
  private volatile boolean forward;
  private volatile boolean cancelled;
  public CommandExecuteEvent(CommandSource source, String command) {
    this.source = source; this.original = command; this.command = command;
  }
  public CommandSource source() { return source; }
  /** The command as it stands, rewritten or not. */
  public String command() { return command; }
  /** The command as the player typed it. */
  public String originalCommand() { return original; }
  /** Replaces the command; a leading slash is taken off. */
  public void setCommand(String command) {
    if (command == null) throw new IllegalArgumentException("command is required");
    this.command = command.startsWith("/") ? command.substring(1) : command;
  }
  /** Sends the command to the player's backend, even when the proxy has a command by that name. */
  public void forwardToServer() { this.forward = true; }
  public boolean forwardsToServer() { return forward; }
  @Override public boolean cancelled() { return cancelled; }
  @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
}
