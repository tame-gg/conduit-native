package gg.tame.conduit.config;

import gg.tame.conduit.modded.ModLoaderFamily;
import java.net.InetSocketAddress;
import java.util.Set;

/** Immutable native backend definition. */
public record BackendServer(String name, InetSocketAddress address, Set<ModLoaderFamily> supportedModLoaders) {
  public BackendServer(String name, InetSocketAddress address) {
    this(name, address, Set.of());
  }

  public BackendServer {
    if (!name.matches("[a-zA-Z0-9_-]{1,64}")) throw new IllegalArgumentException("backend name is invalid: " + name);
    if (address.getPort() < 1 || address.getPort() > 65535) throw new IllegalArgumentException("backend port must be 1..65535");
    if (supportedModLoaders == null) supportedModLoaders = Set.of();
    else supportedModLoaders = Set.copyOf(supportedModLoaders);
  }

  /** Empty set means unrestricted (all families allowed). */
  public boolean accepts(ModLoaderFamily family) {
    if (supportedModLoaders.isEmpty()) return true;
    return supportedModLoaders.contains(family);
  }
}
