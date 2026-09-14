package gg.tame.conduit.api.event.messaging;

import gg.tame.conduit.api.event.Cancellable;
import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.player.Player;

public final class PluginMessageEvent implements Event, Cancellable {
  public enum Direction { CLIENT_TO_PROXY, BACKEND_TO_PROXY }
  private final Player player;
  private final String channel;
  private final byte[] data;
  private final Direction direction;
  private boolean cancelled;
  public PluginMessageEvent(Player player, String channel, byte[] data, Direction direction) {
    this.player = player; this.channel = channel; this.data = data; this.direction = direction;
  }
  public Player player() { return player; }
  public String channel() { return channel; }
  public byte[] data() { return data.clone(); }
  public Direction direction() { return direction; }
  @Override public boolean cancelled() { return cancelled; }
  @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
}
