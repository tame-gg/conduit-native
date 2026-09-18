// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event.player;

import gg.tame.conduit.api.event.Cancellable;
import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.api.server.RegisteredServer;
import java.util.Optional;

/**
 * The player is about to be connected to {@link #target()}: their first backend ({@code source}
 * empty), a switch, or a fallback after their backend was lost.
 *
 * <p>Cancelling stops it before any backend connection is opened: a switch leaves the player where
 * they are, and a first connection or fallback moves on to the next candidate server.
 * {@link #setTarget} sends them somewhere else instead. Fired on the thread running the
 * connection, which waits for every listener.
 */
public final class PlayerServerConnectEvent implements Event, Cancellable {
  private final Player player;
  private final Optional<RegisteredServer> source;
  private final RegisteredServer original;
  private volatile RegisteredServer target;
  private volatile boolean cancelled;
  public PlayerServerConnectEvent(Player player, Optional<RegisteredServer> source, RegisteredServer target) {
    this.player = player; this.source = source; this.original = target; this.target = target;
  }
  public Player player() { return player; }
  public Optional<RegisteredServer> source() { return source; }
  /** Where the player will be sent: the requested server unless a listener redirected it. */
  public RegisteredServer target() { return target; }
  public RegisteredServer originalTarget() { return original; }
  /** Redirects the connection. The server must be registered with the proxy, or the connection fails. */
  public void setTarget(RegisteredServer target) {
    if (target == null) throw new IllegalArgumentException("target is required; cancel instead");
    this.target = target;
  }
  @Override public boolean cancelled() { return cancelled; }
  @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
}
