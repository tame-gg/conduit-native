package gg.tame.conduit.compat.velocity;

import gg.tame.conduit.api.ConduitProxy;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.api.server.RegisteredServer;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/** Thin adapter. Not the Velocity API; Velocity plugins must be compiled against this later. */
public final class VelocityProxyAdapter {
  private final ConduitProxy proxy;
  public VelocityProxyAdapter(ConduitProxy proxy) { this.proxy = proxy; }
  public Optional<Player> getPlayer(UUID uniqueId) { return proxy.player(uniqueId); }
  public Optional<Player> getPlayer(String username) { return proxy.player(username); }
  public Collection<Player> getAllPlayers() { return proxy.players().all(); }
  public Optional<RegisteredServer> getServer(String name) { return proxy.servers().getServer(name); }
  public Collection<RegisteredServer> getAllServers() { return proxy.servers().getServers(); }
}
