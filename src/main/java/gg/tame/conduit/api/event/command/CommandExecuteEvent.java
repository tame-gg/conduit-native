package gg.tame.conduit.api.event.command;

import gg.tame.conduit.api.command.CommandSource;
import gg.tame.conduit.api.event.Cancellable;
import gg.tame.conduit.api.event.Event;

/**
 * A player typed a command, proxy or backend. Fired before Conduit looks the command up.
 *
 * <p>Cancelling drops it: no proxy command runs and nothing reaches the backend. {@link #command()}
 * has no leading slash. Fired on the player's connection thread; do not block.
 */
public final class CommandExecuteEvent implements Event, Cancellable {
  private final CommandSource source;
  private final String command;
  private volatile boolean cancelled;
  public CommandExecuteEvent(CommandSource source, String command) {
    this.source = source; this.command = command;
  }
  public CommandSource source() { return source; }
  public String command() { return command; }
  @Override public boolean cancelled() { return cancelled; }
  @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
}
