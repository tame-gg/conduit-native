package gg.tame.conduit.api.server;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ServerManager {
  Optional<RegisteredServer> getServer(String name);
  Collection<RegisteredServer> getServers();
  /** Adds a backend players can be sent to. Throws IllegalArgumentException when the name is taken. */
  RegisteredServer register(String name, InetSocketAddress address);
  /** Removes a backend; players already on it stay until they leave. */
  boolean unregister(String name);
  /** Where a new player is sent, in the order tried (routing {@code initial}). */
  List<RegisteredServer> initialServers();
  /** Where a player whose backend was lost is sent, in the order tried (routing {@code fallback}). */
  List<RegisteredServer> fallbackServers();
}
