package gg.tame.conduit.api.event.player;

import gg.tame.conduit.api.event.Cancellable;
import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.api.server.RegisteredServer;
import java.util.Optional;

public final class PlayerServerConnectEvent implements Event, Cancellable {
  private final Player player;
  private final Optional<RegisteredServer> source;
  private final RegisteredServer target;
  private boolean cancelled;
  public PlayerServerConnectEvent(Player player, Optional<RegisteredServer> source, RegisteredServer target) {
    this.player = player; this.source = source; this.target = target;
  }
  public Player player() { return player; }
  public Optional<RegisteredServer> source() { return source; }
  public RegisteredServer target() { return target; }
  @Override public boolean cancelled() { return cancelled; }
  @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
}
