// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event.player;

import gg.tame.conduit.api.event.Cancellable;
import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.api.server.RegisteredServer;
import java.util.Optional;
import java.util.UUID;

/**
 * The server the player is on, or is being configured for, takes a resource pack away from them, or
 * every pack when {@link #id()} is empty (1.20.3+ clients, whose packs have ids). Fired on the thread
 * relaying that server's packets, which waits for every listener. Cancelled, the client keeps its packs.
 */
public final class ServerResourcePackRemoveEvent implements Event, Cancellable {
  private final Player player;
  private final RegisteredServer server;
  private final Optional<UUID> id;
  private volatile boolean cancelled;
  public ServerResourcePackRemoveEvent(Player player, RegisteredServer server, Optional<UUID> id) {
    this.player = player; this.server = server; this.id = id;
  }
  public Player player() { return player; }
  public RegisteredServer server() { return server; }
  /** The server's id for the pack, as it offered it; empty for every pack. */
  public Optional<UUID> id() { return id; }
  @Override public boolean cancelled() { return cancelled; }
  @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
}
