// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.routing;

import gg.tame.conduit.api.server.ServerAvailability;
import gg.tame.conduit.api.server.ServerStatus;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.health.BackendHealth;
import gg.tame.conduit.health.BackendHealthService;
import gg.tame.conduit.modded.ModCompatibility;
import gg.tame.conduit.modded.ModLoaderFamily;
import gg.tame.conduit.modded.UnknownModdedPolicy;
import gg.tame.conduit.protocol.BackendStatusProbe;
import gg.tame.conduit.protocol.CompatibilityRegistry;
import gg.tame.conduit.protocol.ProtocolCompatibility;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.TranslationSupport;
import gg.tame.conduit.session.BackendConnection;
import java.io.IOException;
import java.net.Socket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Selects configured initial and fallback backends without transport coupling. */
public final class BackendSelector {
  /** One status ping, on the connect path; short enough not to stall a join for a dead backend. */
  private static final int PROTOCOL_PROBE_TIMEOUT_MS = 1_500;

  private final ConduitConfiguration configuration;
  private final ServerRegistry registry;
  private final BackendHealthService health;
  private final ConcurrentHashMap<String, BackendStatusProbe.Advertisement> advertisements = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, ServerStatus> statuses = new ConcurrentHashMap<>();
  private volatile Instant lastRefresh = Instant.EPOCH;

  public BackendSelector(ConduitConfiguration configuration) {
    this(configuration, null);
  }

  public BackendSelector(ConduitConfiguration configuration, BackendHealthService health) {
    this.configuration = configuration;
    // One registry for both. Health used to keep its own copy of the configured servers, so a server a
    // plugin registered was never probed and could not be drained.
    this.registry = health == null ? new ServerRegistry(configuration) : health.registry();
    this.health = health;
    for (BackendServer server : registry.all()) {
      statuses.put(ServerRegistry.normalize(server.name()), ServerStatus.unknown(server.name()));
    }
  }

  public ServerRegistry registry() { return registry; }
  public Instant lastRefresh() { return lastRefresh; }
  public BackendHealthService health() { return health; }

  /**
   * Unregisters {@code name} and forgets everything learned about it, so a server registered later
   * under the same name -- a cloud system reusing "lobby-1" on a new port -- starts with no health
   * verdict, drain flag or advertised protocol of its namesake's.
   */
  public boolean unregister(String name) {
    boolean removed = registry.unregister(name);
    if (health != null) health.forget(name);
    advertisements.remove(ServerRegistry.normalize(name));
    statuses.remove(ServerRegistry.normalize(name));
    return removed;
  }

  /**
   * Opens the connection a player's login goes over. A backend that cannot even be reached counts
   * against its health (see {@link BackendHealthService#connectFailed}); one that answers and then
   * refuses the player is up, and does not.
   */
  public Socket open(BackendServer server) throws IOException {
    try {
      return BackendConnection.open(server);
    } catch (IOException unreachable) {
      if (health != null) health.connectFailed(server.name());
      throw unreachable;
    }
  }

  /** Health checks have found {@code name} down. Never true while they are off or have no verdict yet. */
  public boolean knownDown(String name) {
    if (health != null) return health.snapshot(name).health() == BackendHealth.UNHEALTHY;
    return status(name).availability() == ServerAvailability.OFFLINE;
  }

  public void probeAll() {
    if (health != null) {
      health.probeOnce();
      lastRefresh = Instant.now();
      return;
    }
    refreshStatus(true);
  }

  /** Quiet refresh used by the scheduler — no stdout spam. */
  public void refreshStatusQuietly() {
    if (health != null) {
      health.probeOnce();
      lastRefresh = Instant.now();
      return;
    }
    refreshStatus(false);
  }

  private void refreshStatus(boolean log) {
    Instant now = Instant.now();
    for (BackendServer server : registry.all()) {
      String key = ServerRegistry.normalize(server.name());
      Optional<BackendStatusProbe.Advertisement> advertisement = BackendStatusProbe.probe(server.address(), 1500);
      if (advertisement.isEmpty()) {
        // Keep the last known protocol so a flaky ping cannot flip TRANSLATED→DIRECT.
        statuses.put(key, ServerStatus.offline(server.name(), now));
        if (log) System.out.println("Backend " + server.name() + " did not answer a status ping.");
        continue;
      }
      BackendStatusProbe.Advertisement ad = advertisement.get();
      advertisements.put(key, ad);
      statuses.put(key, ServerStatus.online(server.name(), ad.protocol(), ad.name(), ad.onlinePlayers(), ad.maxPlayers(), ad.latencyMillis(), now));
      if (log) {
        System.out.println("Backend " + server.name() + ": " + BackendStatusProbe.describe(ad));
        if (!ProtocolDefinition.hasCodec(ad.protocol())) {
          System.out.println("Backend " + server.name() + " is running protocol " + ad.protocol() + ", which Conduit does not yet support.");
        }
      }
    }
    lastRefresh = now;
  }

  public Optional<BackendStatusProbe.Advertisement> advertisement(String name) {
    if (health != null) return health.advertisement(name);
    return Optional.ofNullable(advertisements.get(ServerRegistry.normalize(name)));
  }

  /**
   * The backend's advertised protocol, probing for it now if nothing has been recorded yet.
   *
   * <p>Choosing a translator needs this answer at the moment a player connects, and the periodic
   * health probe only has it once it has run. Callers used to treat "not known yet" as "the same
   * protocol as the client", which is not a fallback but a guess, and a wrong one is invisible: the
   * session is set up as DIRECT and Conduit forwards one version's packets to a server speaking
   * another. A 26.2 client reached a 1.21.8 backend that way, with no translator in the path and
   * nothing in the log to say so.
   *
   * <p>So an unknown backend is asked directly. It is one status ping against a server the player
   * is about to connect to anyway, it is cached for the next caller, and only a backend that will
   * not answer at all is left to the caller's own fallback.
   */
  public Optional<Integer> resolveProtocol(String name) {
    Optional<BackendStatusProbe.Advertisement> known = advertisement(name);
    if (known.isPresent()) return known.map(BackendStatusProbe.Advertisement::protocol);
    // With no translation engine the answer cannot change what happens next: differing protocols
    // are UNSUPPORTED and forwarded as bytes either way. So the ping is spent only where it buys
    // something, and a deployment that has turned translation off keeps exactly one connection
    // per join.
    if (!gg.tame.conduit.viaversion.ConduitViaBootstrap.settings().enabled()) return Optional.empty();
    Optional<BackendServer> server = registry.get(name);
    if (server.isEmpty()) return Optional.empty();
    Optional<BackendStatusProbe.Advertisement> probed =
        BackendStatusProbe.probe(server.get().address(), PROTOCOL_PROBE_TIMEOUT_MS);
    probed.ifPresent(ad -> advertisements.put(ServerRegistry.normalize(name), ad));
    return probed.map(BackendStatusProbe.Advertisement::protocol);
  }

  public ServerStatus status(String name) {
    if (health != null) return health.toServerStatus(name);
    String key = ServerRegistry.normalize(name);
    ServerStatus status = statuses.get(key);
    if (status != null) return status;
    return ServerStatus.unknown(name);
  }

  public List<ServerStatus> allStatuses() {
    List<ServerStatus> list = new ArrayList<>();
    for (BackendServer server : registry.all()) list.add(status(server.name()));
    return List.copyOf(list);
  }

  public void markConnecting(String name) {
    if (health != null) return; // health service owns probe state; connecting is session-local
    String key = ServerRegistry.normalize(name);
    ServerStatus prior = status(name);
    statuses.put(key, new ServerStatus(
        prior.name(),
        ServerAvailability.CONNECTING,
        prior.protocol(),
        prior.versionName(),
        prior.onlinePlayers(),
        prior.maxPlayers(),
        prior.latencyMillis(),
        Instant.now()));
  }

  public TranslationSupport compatibility(int clientProtocol, String backendName) {
    return advertisement(backendName)
        .map(advertisement -> ProtocolCompatibility.between(clientProtocol, advertisement.protocol()))
        .orElse(ProtocolCompatibility.between(clientProtocol, clientProtocol));
  }

  public boolean isCompatible(int clientProtocol, String backendName) {
    return advertisement(backendName)
        .map(advertisement -> CompatibilityRegistry.resolve(clientProtocol, advertisement.protocol()).selectable())
        .orElseGet(() -> ProtocolDefinition.hasCodec(clientProtocol));
  }

  public boolean isEligible(String name, int clientProtocol, boolean allowDrainingBypass) {
    return isEligible(name, clientProtocol, ModLoaderFamily.UNKNOWN, allowDrainingBypass);
  }

  public boolean isEligible(String name, int clientProtocol, ModLoaderFamily clientFamily, boolean allowDrainingBypass) {
    Optional<BackendServer> server = registry.get(name);
    if (server.isEmpty()) return false;
    if (!ProtocolDefinition.hasCodec(clientProtocol)) return false;
    if (health != null) {
      if (health.snapshot(name).health() == BackendHealth.UNHEALTHY) return false;
      if (health.isDraining(name) && !allowDrainingBypass) return false;
    } else if (status(name).availability() == ServerAvailability.OFFLINE) {
      return false;
    }
    if (configuration.modded().enabled()) {
      UnknownModdedPolicy policy = configuration.modded().unknownPolicy();
      if (!ModCompatibility.isEligible(clientFamily == null ? ModLoaderFamily.UNKNOWN : clientFamily, server.get(), policy)) {
        return false;
      }
      ModLoaderFamily family = clientFamily == null ? ModLoaderFamily.UNKNOWN : clientFamily;
      if (family == ModLoaderFamily.FORGE && !configuration.modded().forgeCompat()) return false;
      if (family == ModLoaderFamily.NEOFORGE && !configuration.modded().neoForgeCompat()) return false;
      if (family == ModLoaderFamily.FABRIC && !configuration.modded().fabricCompat()) return false;
    }
    // When Conduit advertises a TRANSLATED/DIRECT path, require selectable completeness.
    // UNSUPPORTED pairs remain eligible so Via-style backends can accept the client wire protocol.
    Optional<BackendStatusProbe.Advertisement> advertisement = advertisement(name);
    if (advertisement.isPresent()) {
      var entry = CompatibilityRegistry.resolve(clientProtocol, advertisement.get().protocol());
      if (entry.support() == TranslationSupport.TRANSLATED || entry.support() == TranslationSupport.DIRECT) {
        return entry.selectable();
      }
    }
    return true;
  }

  public List<BackendServer> candidates() { return named(configuration.initialBackends(), configuration.fallbackBackends()); }

  /** First hop follows routing.initial, then fallback, then any other configured servers. */
  public List<BackendServer> candidatesFor(int clientProtocol) {
    return candidatesFor(clientProtocol, ModLoaderFamily.UNKNOWN, false);
  }

  public List<BackendServer> candidatesFor(int clientProtocol, boolean allowDrainingBypass) {
    return candidatesFor(clientProtocol, ModLoaderFamily.UNKNOWN, allowDrainingBypass);
  }

  public List<BackendServer> candidatesFor(int clientProtocol, ModLoaderFamily clientFamily, boolean allowDrainingBypass) {
    LinkedHashSet<String> orderedNames = new LinkedHashSet<>();
    orderedNames.addAll(configuration.initialBackends());
    orderedNames.addAll(configuration.fallbackBackends());
    for (BackendServer server : registry.all()) orderedNames.add(server.name());
    List<BackendServer> ordered = new ArrayList<>();
    for (String name : orderedNames) {
      if (!isEligible(name, clientProtocol, clientFamily, allowDrainingBypass)) continue;
      registry.get(name).ifPresent(ordered::add);
    }
    return ordered.isEmpty() ? candidates() : ordered;
  }

  public List<BackendServer> fallback(String current, Set<String> failed) {
    return fallback(current, failed, -1, ModLoaderFamily.UNKNOWN, false);
  }

  public List<BackendServer> fallback(String current, Set<String> failed, int clientProtocol, boolean allowDrainingBypass) {
    return fallback(current, failed, clientProtocol, ModLoaderFamily.UNKNOWN, allowDrainingBypass);
  }

  public List<BackendServer> fallback(String current, Set<String> failed, int clientProtocol, ModLoaderFamily clientFamily, boolean allowDrainingBypass) {
    List<String> names = new ArrayList<>(configuration.fallbackBackends());
    names.removeIf(name -> ServerRegistry.normalize(name).equals(ServerRegistry.normalize(current)));
    names.removeIf(name -> failed.contains(ServerRegistry.normalize(name)));
    List<BackendServer> result = new ArrayList<>();
    for (String name : names) {
      if (clientProtocol >= 0 && !isEligible(name, clientProtocol, clientFamily, allowDrainingBypass)) continue;
      if (clientProtocol < 0 && health != null) {
        if (health.snapshot(name).health() == BackendHealth.UNHEALTHY) continue;
        if (health.isDraining(name) && !allowDrainingBypass) continue;
      }
      registry.get(name).ifPresent(result::add);
    }
    if (clientProtocol < 0 && health == null) {
      result.sort((a, b) -> Boolean.compare(status(b.name()).online(), status(a.name()).online()));
    }
    return result;
  }

  private List<BackendServer> named(List<String> first, List<String> extra) {
    LinkedHashSet<String> names = new LinkedHashSet<>(first);
    names.addAll(extra);
    return named(List.copyOf(names));
  }

  /** Configured names a plugin has since unregistered are skipped; looking them up threw and broke the join. */
  private List<BackendServer> named(List<String> names) {
    List<BackendServer> result = new ArrayList<>();
    for (String name : names) registry.get(name).ifPresent(result::add);
    return result;
  }
}
