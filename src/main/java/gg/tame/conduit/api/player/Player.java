package gg.tame.conduit.api.player;

import gg.tame.conduit.api.permission.PermissionSubject;
import gg.tame.conduit.api.server.RegisteredServer;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface Player extends PermissionSubject {
  UUID uniqueId();
  String username();
  boolean authenticated();
  String connectionState();
  OptionalServer currentServer();
  void sendMessage(String message);
  default void sendMessage(gg.tame.conduit.api.text.Text text) {
    sendMessage(text == null ? "" : text.plain());
  }
  CompletableFuture<Boolean> connect(RegisteredServer server);
  void disconnect(String reason);
  void sendPluginMessage(String channel, byte[] data);

  interface OptionalServer {
    boolean isPresent();
    RegisteredServer orElse(RegisteredServer fallback);
    String name();
  }
}
