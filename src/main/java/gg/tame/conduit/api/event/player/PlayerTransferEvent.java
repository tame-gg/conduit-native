// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event.player;

import gg.tame.conduit.api.event.Cancellable;
import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.player.Player;

/**
 * A Transfer packet is about to send a 1.20.5+ player to another address, where the client will log
 * in again by itself: because a plugin called {@code Player.transferToHost}, or because the backend
 * they are on sent one ({@link #fromBackend()}). Cancelling it sends nothing, and the player stays;
 * {@link #setTarget} sends them somewhere else instead. Fired on the thread that asked for the
 * transfer: the plugin's own, or the player's backend reader. Do not block.
 */
public final class PlayerTransferEvent implements Event, Cancellable {
  private final Player player;
  private final boolean fromBackend;
  private volatile String host;
  private volatile int port;
  private volatile boolean cancelled;

  public PlayerTransferEvent(Player player, String host, int port, boolean fromBackend) {
    this.player = player; this.host = host; this.port = port; this.fromBackend = fromBackend;
  }
  public Player player() { return player; }
  public String host() { return host; }
  public int port() { return port; }
  /** Whether the backend the player is on sent the transfer, rather than a plugin. */
  public boolean fromBackend() { return fromBackend; }
  /** @throws IllegalArgumentException for a blank host or a port outside 1 to 65535 */
  public void setTarget(String host, int port) {
    if (host == null || host.isBlank()) throw new IllegalArgumentException("host is required");
    if (port < 1 || port > 65535) throw new IllegalArgumentException("port out of range: " + port);
    this.host = host; this.port = port;
  }
  @Override public boolean cancelled() { return cancelled; }
  @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
}
