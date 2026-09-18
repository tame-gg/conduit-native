package gg.tame.conduit.api.event.messaging;

import gg.tame.conduit.api.event.Cancellable;
import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.player.Player;

/**
 * A plugin message is passing through the proxy: from the client on its way to the backend
 * ({@code CLIENT_TO_PROXY}), or from the backend on its way to the client ({@code BACKEND_TO_PROXY}).
 * Cancelling drops it; otherwise it is forwarded unchanged. Channels the security guard blocks
 * never get here.
 *
 * <p>Fired on the reader thread for that direction, in order with the rest of that stream, so a
 * slow listener stalls the player's traffic. Do not block.
 */
public final class PluginMessageEvent implements Event, Cancellable {
  public enum Direction { CLIENT_TO_PROXY, BACKEND_TO_PROXY }
  private final Player player;
  private final String channel;
  private final byte[] data;
  private final Direction direction;
  private volatile boolean cancelled;
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
