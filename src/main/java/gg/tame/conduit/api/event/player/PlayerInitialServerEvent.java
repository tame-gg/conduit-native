package gg.tame.conduit.api.event.player;

import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.api.server.RegisteredServer;
import java.util.Optional;

/**
 * Chooses the first backend for a player who just logged in. {@link #initialServer()} starts as
 * the first server routing would try; {@link #setInitialServer} puts another one first, and
 * routing's other candidates stay behind it as fallbacks. Fired after an allowed
 * {@link PlayerLoginEvent}, on the connection's thread.
 */
public final class PlayerInitialServerEvent implements Event {
  private final Player player;
  private volatile RegisteredServer initial;
  public PlayerInitialServerEvent(Player player, RegisteredServer initial) {
    this.player = player; this.initial = initial;
  }
  public Player player() { return player; }
  /** Empty when routing has no server for this client and no listener chose one. */
  public Optional<RegisteredServer> initialServer() { return Optional.ofNullable(initial); }
  /** The server must be registered with the proxy; an unknown one is skipped. */
  public void setInitialServer(RegisteredServer server) {
    if (server == null) throw new IllegalArgumentException("server is required");
    this.initial = server;
  }
}
