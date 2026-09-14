package gg.tame.conduit.api.event.player;

import gg.tame.conduit.api.event.Cancellable;
import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.player.Player;

public final class PlayerChatEvent implements Event, Cancellable {
  private final Player player;
  private final String message;
  private boolean cancelled;
  public PlayerChatEvent(Player player, String message) {
    this.player = player; this.message = message;
  }
  public Player player() { return player; }
  public String message() { return message; }
  @Override public boolean cancelled() { return cancelled; }
  @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
}
