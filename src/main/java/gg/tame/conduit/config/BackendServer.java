// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.config;

import gg.tame.conduit.modded.ModLoaderFamily;
import java.net.InetSocketAddress;
import java.util.Set;

/** Immutable native backend definition. */
public record BackendServer(String name, InetSocketAddress address, Set<ModLoaderFamily> supportedModLoaders, int maxPlayers) {
  public BackendServer(String name, InetSocketAddress address) {
    this(name, address, Set.of());
  }

  /** No player limit. */
  public BackendServer(String name, InetSocketAddress address, Set<ModLoaderFamily> supportedModLoaders) {
    this(name, address, supportedModLoaders, 0);
  }

  /** Whether the proxy sends nobody else here once {@code online} players are on it. Zero is no limit. */
  public boolean full(int online) { return maxPlayers > 0 && online >= maxPlayers; }

  public BackendServer {
    if (maxPlayers < 0) throw new IllegalArgumentException("max-players must be >= 0");
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
