package gg.tame.conduit.api.server;

import gg.tame.conduit.api.player.Player;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Configured backend. Address is for plugins that already have it; forwarding secrets stay hidden. */
public interface RegisteredServer {
  String getName();
  InetSocketAddress getAddress();
  boolean isOnline();
  default boolean isDraining() { return false; }
  default gg.tame.conduit.api.server.ServerStatus status() {
    return isOnline()
        ? gg.tame.conduit.api.server.ServerStatus.online(getName(), -1, "", -1, -1, -1, java.time.Instant.now())
        : gg.tame.conduit.api.server.ServerStatus.offline(getName(), java.time.Instant.now());
  }
  CompletableFuture<Boolean> connect(Player player);
  /** Players on this backend right now. Empty for a server the proxy does not have registered. */
  default Collection<Player> players() { return List.of(); }
  /**
   * Sends a plugin message to this backend through one of the players on it, which is the only
   * connection the proxy has to it. False when nobody is on it.
   */
  default boolean sendPluginMessage(String channel, byte[] data) {
    for (Player player : players()) if (player.sendPluginMessageToServer(channel, data)) return true;
    return false;
  }
}
