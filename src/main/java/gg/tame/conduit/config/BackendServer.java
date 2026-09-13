package gg.tame.conduit.config;

import java.net.InetSocketAddress;

/** Immutable native backend definition. */
public record BackendServer(String name, InetSocketAddress address) {
  public BackendServer {
    if (!name.matches("[a-zA-Z0-9_-]{1,64}")) throw new IllegalArgumentException("backend name is invalid: " + name);
    if (address.getPort() < 1 || address.getPort() > 65535) throw new IllegalArgumentException("backend port must be 1..65535");
  }
}
