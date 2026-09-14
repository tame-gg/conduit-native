package gg.tame.conduit.api.server;

import gg.tame.conduit.api.player.Player;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

/** Configured backend. Address is for plugins that already have it; forwarding secrets stay hidden. */
public interface RegisteredServer {
  String getName();
  InetSocketAddress getAddress();
  boolean isOnline();
  CompletableFuture<Boolean> connect(Player player);
}
