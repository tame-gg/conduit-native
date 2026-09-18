// SPDX-License-Identifier: GPL-3.0-or-later
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
  /**
   * Asks the backend for its status now, rather than reading the cached {@link #status()}: version,
   * player counts and sample, description, favicon, and the round trip in {@code latencyMillis}.
   * Same as {@code ping(-1, null, null)}. This default answers with the cached status.
   */
  default CompletableFuture<ServerStatus> ping() { return CompletableFuture.completedFuture(status()); }
  /**
   * {@link #ping()}, choosing what the status handshake says.
   *
   * <p>{@code protocol} is the client version the handshake announces; {@code -1} names none, and
   * the backend answers with its own version rather than one tailored to a client (a backend running
   * ViaVersion may otherwise echo the version it was asked about). {@code virtualHost} is the host
   * the handshake says was dialled; null uses the host of the server's configured address.
   * {@code timeout} bounds the whole ping, from this call to the last byte of the answer,
   * including any wait for a free ping thread; null, zero or negative uses the proxy's health-check
   * timeout.
   *
   * <p>Never blocks the caller. Completes on a Conduit ping thread, so a dependent stage that blocks
   * should use an {@code *Async} method. Never completes exceptionally: a backend that cannot be
   * reached, does not answer in time, or answers with something that is not a status (malformed or
   * oversized JSON, a wrong packet, a connection closed mid-answer) gives an
   * {@link ServerAvailability#OFFLINE} status, as does a ping refused because too many are already
   * waiting. The cached {@link #status()} and the health checks are left alone. This default
   * ignores the options.
   */
  default CompletableFuture<ServerStatus> ping(int protocol, String virtualHost, java.time.Duration timeout) { return ping(); }
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
