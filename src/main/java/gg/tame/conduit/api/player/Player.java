package gg.tame.conduit.api.player;

import gg.tame.conduit.api.command.CommandSource;
import gg.tame.conduit.api.server.RegisteredServer;
import gg.tame.conduit.api.text.Text;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * A connected player. Every method may be called from any thread. Messages and plugin messages
 * sent before the player reaches Play are dropped rather than written in the wrong state.
 */
public interface Player extends CommandSource {
  UUID uniqueId();
  String username();
  boolean authenticated();
  String connectionState();
  OptionalServer currentServer();
  /** Protocol number the client joined with, e.g. 47 for 1.8.9 or 765 for 1.20.4. */
  int protocolVersion();
  /**
   * Where the player connected from: the socket's peer, or the forwarded address when one is
   * configured. An address without a port, because a forwarded one has none.
   */
  InetAddress remoteAddress();
  /** Host and port the client says it dialled, from its handshake, unresolved. Forge markers are removed. */
  InetSocketAddress virtualHost();
  void sendMessage(String message);
  default void sendMessage(Text text) {
    sendMessage(text == null ? "" : text.plain());
  }
  /**
   * Moves the player to {@code server} without telling them anything. Completes true when they
   * arrived. Use {@link #connectWithResult} to learn why they did not.
   */
  CompletableFuture<Boolean> connect(RegisteredServer server);
  /**
   * Moves the player to {@code server}, firing {@code PlayerServerConnectEvent} first. Never
   * completes exceptionally, and never on the calling thread's time: the switch runs on its own
   * thread, so it is safe to call from a listener or a command.
   */
  CompletableFuture<ConnectResult> connectWithResult(RegisteredServer server);
  /** Kicks the player with {@code reason} on their disconnect screen, in whatever state they are in. */
  void disconnect(String reason);
  default void disconnect(Text reason) {
    disconnect(reason == null ? "" : reason.plain());
  }
  /** Sends a plugin message to the player's client. */
  void sendPluginMessage(String channel, byte[] data);
  /**
   * Sends a plugin message to the backend the player is on, as if the client had sent it. False
   * when there is no backend or it is not in a state that takes one.
   */
  boolean sendPluginMessageToServer(String channel, byte[] data);

  interface OptionalServer {
    boolean isPresent();
    RegisteredServer orElse(RegisteredServer fallback);
    String name();
  }
}
