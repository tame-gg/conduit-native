package gg.tame.conduit.api.server;

import gg.tame.conduit.api.player.Player;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

/** Configured backend. Address is for plugins that already have it; forwarding secrets stay hidden. */
public interface RegisteredServer {
  String getName();
  InetSocketAddress getAddress();
  boolean isOnline();
  default gg.tame.conduit.api.server.ServerStatus status() {
    return isOnline()
        ? gg.tame.conduit.api.server.ServerStatus.online(getName(), -1, "", -1, -1, -1, java.time.Instant.now())
        : gg.tame.conduit.api.server.ServerStatus.offline(getName(), java.time.Instant.now());
  }
  CompletableFuture<Boolean> connect(Player player);
}
