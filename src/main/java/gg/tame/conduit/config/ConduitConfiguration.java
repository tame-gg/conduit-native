// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.config;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public record ConduitConfiguration(InetSocketAddress listener, int maxFrameBytes,
                                  ForwardingMode forwardingMode, Optional<Path> forwardingSecretFile,
                                  List<BackendServer> backends, List<String> initialBackends,
                                  List<String> fallbackBackends, AuthenticationSettings authentication,
                                  Optional<java.net.InetAddress> forwardedPlayerAddress,
                                  OpsSettings ops, boolean proxyProtocol,
                                  gg.tame.conduit.routing.ForcedHosts forcedHosts,
                                  int compressionThreshold, int maxConnections) {
  /** What {@code listener.compression-threshold} is when the file does not say. */
  public static final int DEFAULT_COMPRESSION_THRESHOLD = 256;
  /**
   * What {@code listener.max-connections} is for a record built in code rather than read from a
   * file. A configuration file that does not set it gets {@link ConnectionCapacity#automatic()},
   * which reads the machine instead of guessing; this is the plain number the tests and the API's
   * own constructors want, and is deliberately small enough to be obviously a default.
   */
  public static final int DEFAULT_MAX_CONNECTIONS = 2048;
  /**
   * The most {@code listener.max-connections} may be, and what {@code 0} means.
   *
   * <p>There is no unlimited setting. A limit is what lets the accept loop refuse a connection it
   * cannot serve instead of accepting it and running the process out of descriptors, so removing the
   * limit removes the only thing standing between a flood and the operating system -- and the
   * refusal at least says which setting to raise, where {@code Too many open files} does not.
   *
   * <p>The number is a ceiling rather than a measured capacity: 350000 connections is 700000 sockets
   * and, in the worst case, more heap than any one instance has, so an operator reaching it should be
   * running more than one proxy. It is here to catch a typo -- a stray zero on a limit is a promise
   * the machine cannot keep -- not to describe what one instance holds.
   */
  public static final int MAX_MAX_CONNECTIONS = 350_000;
  /**
   * Every setting but the client link's compression, which is off: what a test reads off the wire is
   * then the packet itself. A configuration file gets {@link #DEFAULT_COMPRESSION_THRESHOLD}.
   */
  public ConduitConfiguration(InetSocketAddress listener, int maxFrameBytes, ForwardingMode forwardingMode,
                             Optional<Path> forwardingSecretFile, List<BackendServer> backends,
                             List<String> initialBackends, List<String> fallbackBackends,
                             AuthenticationSettings authentication,
                             Optional<java.net.InetAddress> forwardedPlayerAddress, OpsSettings ops,
                             boolean proxyProtocol, gg.tame.conduit.routing.ForcedHosts forcedHosts) {
    this(listener, maxFrameBytes, forwardingMode, forwardingSecretFile, backends, initialBackends, fallbackBackends,
        authentication, forwardedPlayerAddress, ops, proxyProtocol, forcedHosts, -1, DEFAULT_MAX_CONNECTIONS);
  }
  /** Every setting but the connection limit, which is {@link #DEFAULT_MAX_CONNECTIONS}. */
  public ConduitConfiguration(InetSocketAddress listener, int maxFrameBytes, ForwardingMode forwardingMode,
                             Optional<Path> forwardingSecretFile, List<BackendServer> backends,
                             List<String> initialBackends, List<String> fallbackBackends,
                             AuthenticationSettings authentication,
                             Optional<java.net.InetAddress> forwardedPlayerAddress, OpsSettings ops,
                             boolean proxyProtocol, gg.tame.conduit.routing.ForcedHosts forcedHosts,
                             int compressionThreshold) {
    this(listener, maxFrameBytes, forwardingMode, forwardingSecretFile, backends, initialBackends, fallbackBackends,
        authentication, forwardedPlayerAddress, ops, proxyProtocol, forcedHosts, compressionThreshold,
        DEFAULT_MAX_CONNECTIONS);
  }
  /** Every setting but the forced hosts, of which there are none. */
  public ConduitConfiguration(InetSocketAddress listener, int maxFrameBytes, ForwardingMode forwardingMode,
                             Optional<Path> forwardingSecretFile, List<BackendServer> backends,
                             List<String> initialBackends, List<String> fallbackBackends,
                             AuthenticationSettings authentication,
                             Optional<java.net.InetAddress> forwardedPlayerAddress, OpsSettings ops,
                             boolean proxyProtocol) {
    this(listener, maxFrameBytes, forwardingMode, forwardingSecretFile, backends, initialBackends, fallbackBackends,
        authentication, forwardedPlayerAddress, ops, proxyProtocol, gg.tame.conduit.routing.ForcedHosts.none());
  }

  /** Every setting but the PROXY protocol one, which is off. */
  public ConduitConfiguration(InetSocketAddress listener, int maxFrameBytes, ForwardingMode forwardingMode,
                             Optional<Path> forwardingSecretFile, List<BackendServer> backends,
                             List<String> initialBackends, List<String> fallbackBackends,
                             AuthenticationSettings authentication,
                             Optional<java.net.InetAddress> forwardedPlayerAddress, OpsSettings ops) {
    this(listener, maxFrameBytes, forwardingMode, forwardingSecretFile, backends, initialBackends, fallbackBackends,
        authentication, forwardedPlayerAddress, ops, false);
  }
  public ConduitConfiguration(InetSocketAddress listener, int maxFrameBytes, ForwardingMode forwardingMode,
                             Optional<Path> forwardingSecretFile, List<BackendServer> backends,
                             List<String> initialBackends, List<String> fallbackBackends) {
    this(listener, maxFrameBytes, forwardingMode, forwardingSecretFile, backends, initialBackends, fallbackBackends,
        AuthenticationSettings.offline(), Optional.empty(), OpsSettings.defaults());
  }
  public ConduitConfiguration(InetSocketAddress listener, int maxFrameBytes, ForwardingMode forwardingMode,
                             Optional<Path> forwardingSecretFile, List<BackendServer> backends,
                             List<String> initialBackends, List<String> fallbackBackends,
                             AuthenticationSettings authentication) {
    this(listener, maxFrameBytes, forwardingMode, forwardingSecretFile, backends, initialBackends, fallbackBackends,
        authentication, Optional.empty(), OpsSettings.defaults());
  }
  public ConduitConfiguration(InetSocketAddress listener, int maxFrameBytes, ForwardingMode forwardingMode,
                             Optional<Path> forwardingSecretFile, List<BackendServer> backends,
                             List<String> initialBackends, List<String> fallbackBackends,
                             AuthenticationSettings authentication,
                             Optional<java.net.InetAddress> forwardedPlayerAddress) {
    this(listener, maxFrameBytes, forwardingMode, forwardingSecretFile, backends, initialBackends, fallbackBackends,
        authentication, forwardedPlayerAddress, OpsSettings.defaults());
  }
  public ConduitConfiguration {
    if (listener.getPort() < 1 || listener.getPort() > 65535) throw new IllegalArgumentException("listener.port must be 1..65535");
    if (maxFrameBytes < 1 || maxFrameBytes > 8 * 1024 * 1024) throw new IllegalArgumentException("listener.max-frame-bytes must be 1..8388608");
    if (compressionThreshold < -1) throw new IllegalArgumentException("listener.compression-threshold must be -1 (off) or at least 0");
    if (maxConnections < 0 || maxConnections > MAX_MAX_CONNECTIONS) {
      throw new IllegalArgumentException("listener.max-connections must be 1.." + MAX_MAX_CONNECTIONS
          + ", or 0 for the most Conduit allows (" + MAX_MAX_CONNECTIONS + ")");
    }
    // 0 resolves here rather than at the accept, so every reader of this record -- the accept loop,
    // the line said at start, a plugin asking -- sees the number actually in force.
    if (maxConnections == 0) maxConnections = MAX_MAX_CONNECTIONS;
    if (forwardingMode == ForwardingMode.MODERN && forwardingSecretFile.isEmpty()) throw new IllegalArgumentException("forwarding.secret-file is required for modern forwarding");
    if (forwardingMode == ForwardingMode.BUNGEEGUARD && forwardingSecretFile.isEmpty()) throw new IllegalArgumentException("forwarding.secret-file is required for bungeeguard forwarding: it holds the token");
    // A secret file in another mode used to be refused. It is now the default in every mode: the
    // file is generated on first start so that turning modern forwarding on later is one line here
    // and a copy into each backend. Only ForwardingMode.MODERN reads it; in any other mode it sits
    // unused, which is not a configuration mistake.
    if (authentication == null) authentication = AuthenticationSettings.offline();
    if (forwardedPlayerAddress == null) forwardedPlayerAddress = Optional.empty();
    if (ops == null) ops = OpsSettings.defaults();
    if (forcedHosts == null) forcedHosts = gg.tame.conduit.routing.ForcedHosts.none();
    backends = List.copyOf(backends);
    initialBackends = List.copyOf(initialBackends);
    fallbackBackends = List.copyOf(fallbackBackends);
    if (backends.isEmpty()) throw new IllegalArgumentException("at least one [servers.<name>] backend is required");
    if (initialBackends.isEmpty()) throw new IllegalArgumentException("routing.initial must name at least one backend");
    for (String name : initialBackends) requireBackend(backends, "routing.initial", name);
    for (String name : fallbackBackends) requireBackend(backends, "routing.fallback", name);
    // A forced host naming a server that is not configured is told at start, not at the join it would
    // spoil: the player still lands somewhere, so refusing to start over a typo would cost more than
    // it saves.
    for (var forced : forcedHosts.all().entrySet()) {
      for (String name : forced.getValue()) {
        if (backends.stream().anyMatch(backend -> backend.name().equalsIgnoreCase(name))) continue;
        gg.tame.conduit.log.ConduitLog.warn("forced-hosts." + forced.getKey() + " names unknown server " + name
            + ", which is ignored; players reaching that host follow routing.initial");
      }
    }
  }
  private static void requireBackend(List<BackendServer> backends, String key, String name) {
    if (backends.stream().anyMatch(backend -> backend.name().equals(name))) return;
    // Routing names are matched exactly, although lookups elsewhere ignore case.
    String hint = backends.stream().map(BackendServer::name).filter(name::equalsIgnoreCase).findFirst()
        .map(match -> " (did you mean " + match + "?)").orElse("");
    throw new IllegalArgumentException(key + " names unknown server " + name + hint);
  }

  public MaintenanceSettings maintenance() { return ops.maintenance(); }
  public HealthSettings health() { return ops.health(); }
  public VersionGateSettings versions() { return ops.versions(); }
  public ShutdownSettings shutdown() { return ops.shutdown(); }
  public SecuritySettings security() { return ops.security(); }
  public ModdedSettings modded() { return ops.modded(); }
  public TranslationSettings translation() { return ops.translation(); }
  public StatusSettings status() { return ops.status(); }

  public ConduitConfiguration withOps(OpsSettings replacement) {
    return new ConduitConfiguration(listener, maxFrameBytes, forwardingMode, forwardingSecretFile, backends,
        initialBackends, fallbackBackends, authentication, forwardedPlayerAddress, replacement, proxyProtocol,
        forcedHosts, compressionThreshold, maxConnections);
  }

  /**
   * The same settings with another connection limit, for a reload: the accept loop reads the limit
   * on every connection, so raising it takes effect without a restart. Needed on its own because a
   * reload that also changed something restart-only keeps the rest of this block as it was, and the
   * limit is the one setting in it an operator should not have to drop every session to raise.
   *
   * <p>{@code replacement} goes through the same validation and the same resolution of 0 as a limit
   * read from the file, so a reload cannot install one the file could not have asked for.
   */
  public ConduitConfiguration withMaxConnections(int replacement) {
    if (replacement == maxConnections) return this;
    return new ConduitConfiguration(listener, maxFrameBytes, forwardingMode, forwardingSecretFile, backends,
        initialBackends, fallbackBackends, authentication, forwardedPlayerAddress, ops, proxyProtocol,
        forcedHosts, compressionThreshold, replacement);
  }

  /** Whether a kick nobody handled moves the player on rather than off; see RoutingSettings. */
  public boolean fallbackOnKick() { return ops().routing().fallbackOnKick(); }
}
