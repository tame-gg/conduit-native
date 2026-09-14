package gg.tame.conduit.api.server;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Optional;

public interface ServerManager {
  Optional<RegisteredServer> getServer(String name);
  Collection<RegisteredServer> getServers();
  RegisteredServer register(String name, InetSocketAddress address);
  boolean unregister(String name);
}
