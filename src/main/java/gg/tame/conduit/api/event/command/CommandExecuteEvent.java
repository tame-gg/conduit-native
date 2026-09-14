package gg.tame.conduit.api.event.command;

import gg.tame.conduit.api.event.Cancellable;
import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.command.CommandSource;

public final class CommandExecuteEvent implements Event, Cancellable {
  private final CommandSource source;
  private final String command;
  private boolean cancelled;
  public CommandExecuteEvent(CommandSource source, String command) {
    this.source = source; this.command = command;
  }
  public CommandSource source() { return source; }
  public String command() { return command; }
  @Override public boolean cancelled() { return cancelled; }
  @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
}
