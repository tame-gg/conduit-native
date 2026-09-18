// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event.player;

import gg.tame.conduit.api.event.Cancellable;
import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.api.player.ResourcePack;
import gg.tame.conduit.api.server.RegisteredServer;

/**
 * The server the player is on, or is being configured for, offers them a resource pack, in Play or in
 * the Configuration phase. Fired on the thread relaying that server's packets, which waits for every
 * listener before the offer goes on to the client.
 *
 * <p>Cancelled, the client never sees the pack, and the server is told the client declined it, as
 * the client would have said; a server that requires the pack then does what it does with a refusal.
 * {@link #setPack} offers the client another pack instead: the client's answers about it still reach
 * the server as answers about the pack it offered (its id from 1.20.3, its hash on 1.8), and the
 * server removing its pack removes the one the client got.
 */
public final class ServerResourcePackOfferEvent implements Event, Cancellable {
  private final Player player;
  private final RegisteredServer server;
  private final ResourcePack offered;
  private volatile ResourcePack pack;
  private volatile boolean cancelled;
  public ServerResourcePackOfferEvent(Player player, RegisteredServer server, ResourcePack offered) {
    this.player = player; this.server = server; this.offered = offered; this.pack = offered;
  }
  public Player player() { return player; }
  public RegisteredServer server() { return server; }
  /** The pack as the server offered it. Before 1.20.3 its id is derived from its URL and hash. */
  public ResourcePack offered() { return offered; }
  /** What the client will be offered: the server's pack unless a listener put another in its place. */
  public ResourcePack pack() { return pack; }
  public void setPack(ResourcePack pack) {
    this.pack = java.util.Objects.requireNonNull(pack, "pack; cancel the event to keep the offer from the client");
  }
  @Override public boolean cancelled() { return cancelled; }
  @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
}
