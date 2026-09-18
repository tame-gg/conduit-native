// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.health;

import gg.tame.conduit.api.server.ServerAvailability;
import gg.tame.conduit.api.server.ServerStatus;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.HealthSettings;
import gg.tame.conduit.log.ConduitLog;
import gg.tame.conduit.metrics.ConduitMetrics;
import gg.tame.conduit.protocol.BackendStatusProbe;
import gg.tame.conduit.routing.ServerRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Async backend health with hysteresis and orthogonal drain flags.
 * Unhealthy is a routing signal — it never disconnects existing players.
 */
public final class BackendHealthService implements AutoCloseable {
  private final ServerRegistry registry;
  private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();
  private final Set<String> drained = ConcurrentHashMap.newKeySet();
  private final ConcurrentHashMap<String, BackendStatusProbe.Advertisement> advertisements = new ConcurrentHashMap<>();
  private final AtomicBoolean closed = new AtomicBoolean();
  private volatile HealthSettings settings;
  private volatile ScheduledExecutorService scheduler;

  public BackendHealthService(ServerRegistry registry, HealthSettings settings) {
    this.registry = registry;
    this.settings = settings;
    for (BackendServer server : registry.all()) {
      states.put(ServerRegistry.normalize(server.name()), new State(server.name()));
    }
  }

  public void start() {
    if (!settings.enabled()) return;
    restartScheduler();
  }

  public synchronized void applySettings(HealthSettings replacement) {
    this.settings = replacement;
    if (closed.get()) return;
    if (!replacement.enabled()) {
      stopScheduler();
      return;
    }
    restartScheduler();
  }

  public HealthSettings settings() { return settings; }

  public void probeOnce() {
    for (BackendServer server : registry.all()) probe(server);
  }

  /** Apply a probe outcome without network I/O (tests and injected results). */
  public void applyProbeResult(String name, boolean success) {
    String key = ServerRegistry.normalize(name);
    State state = states.computeIfAbsent(key, ignored -> new State(name));
    Instant now = Instant.now();
    synchronized (state) {
      state.lastProbe = now;
      if (success) {
        state.lastOk = true;
        state.failures = 0;
        state.successes = Math.min(settings.successThreshold(), state.successes + 1);
        BackendHealth previous = state.health;
        if (state.successes >= settings.successThreshold()) state.health = BackendHealth.HEALTHY;
        if (previous != state.health && state.health == BackendHealth.HEALTHY) {
          ConduitLog.info("Backend " + name + " is healthy.");
        }
      } else {
        // Keep the last successful advertisement. Falling back to the client
        // protocol when the ad is absent silently turns TRANSLATED into DIRECT
        // (1.13 client → 1.20.4 handshake as 393 → outdated_client).
        state.lastOk = false;
        state.successes = 0;
        state.failures = Math.min(settings.failureThreshold(), state.failures + 1);
        BackendHealth previous = state.health;
        if (state.failures >= settings.failureThreshold()) state.health = BackendHealth.UNHEALTHY;
        if (previous != state.health && state.health == BackendHealth.UNHEALTHY) {
          ConduitLog.warn("Backend " + name + " is unhealthy.");
          ConduitMetrics.current().backendUnhealthy();
        }
      }
    }
  }

  private void restartScheduler() {
    stopScheduler();
    ScheduledExecutorService next = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread thread = new Thread(r, "conduit-health");
      thread.setDaemon(true);
      return thread;
    });
    scheduler = next;
    long interval = Math.max(500, settings.intervalMs());
    next.scheduleWithFixedDelay(this::safeProbeAll, interval, interval, TimeUnit.MILLISECONDS);
  }

  private void stopScheduler() {
    ScheduledExecutorService current = scheduler;
    scheduler = null;
    if (current != null) current.shutdownNow();
  }

  private void safeProbeAll() {
    try { probeOnce(); }
    catch (RuntimeException exception) { ConduitLog.warn("health probe round failed: " + exception.getMessage()); }
  }

  private void probe(BackendServer server) {
    Optional<BackendStatusProbe.Advertisement> advertisement =
        BackendStatusProbe.probe(server.address(), settings.timeoutMs());
    if (advertisement.isPresent()) {
      advertisements.put(ServerRegistry.normalize(server.name()), advertisement.get());
      applyProbeResult(server.name(), true);
    } else {
      applyProbeResult(server.name(), false);
    }
  }

  public BackendHealthSnapshot snapshot(String name) {
    String key = ServerRegistry.normalize(name);
    State state = states.get(key);
    if (state == null) {
      return new BackendHealthSnapshot(name, BackendHealth.UNKNOWN, drained.contains(key), 0, 0, Instant.EPOCH, false);
    }
    synchronized (state) {
      return new BackendHealthSnapshot(state.name, state.health, drained.contains(key),
          state.failures, state.successes, state.lastProbe, state.lastOk);
    }
  }

  public List<BackendHealthSnapshot> all() {
    List<BackendHealthSnapshot> list = new ArrayList<>();
    for (BackendServer server : registry.all()) list.add(snapshot(server.name()));
    return List.copyOf(list);
  }

  public boolean isRoutable(String name) {
    BackendHealthSnapshot snap = snapshot(name);
    return snap.routable() || (snap.health() == BackendHealth.UNKNOWN && !snap.draining());
  }

  public boolean isHealthy(String name) {
    BackendHealth health = snapshot(name).health();
    return health == BackendHealth.HEALTHY || health == BackendHealth.UNKNOWN;
  }

  public boolean isDraining(String name) {
    return drained.contains(ServerRegistry.normalize(name));
  }

  public boolean drain(String name) {
    if (registry.get(name).isEmpty()) return false;
    return drained.add(ServerRegistry.normalize(name));
  }

  public boolean undrain(String name) {
    return drained.remove(ServerRegistry.normalize(name));
  }

  public Set<String> drainedServers() { return Set.copyOf(drained); }

  public Optional<BackendStatusProbe.Advertisement> advertisement(String name) {
    return Optional.ofNullable(advertisements.get(ServerRegistry.normalize(name)));
  }

  public ServerStatus toServerStatus(String name) {
    BackendHealthSnapshot snap = snapshot(name);
    Optional<BackendStatusProbe.Advertisement> ad = advertisement(name);
    if (snap.draining()) {
      if (ad.isPresent()) {
        BackendStatusProbe.Advertisement a = ad.get();
        return new ServerStatus(name, ServerAvailability.DEGRADED, java.util.OptionalInt.of(a.protocol()), a.name(),
            java.util.OptionalInt.of(a.onlinePlayers()), java.util.OptionalInt.of(a.maxPlayers()),
            java.util.OptionalLong.of(a.latencyMillis()), snap.lastProbe());
      }
      return new ServerStatus(name, ServerAvailability.DEGRADED, java.util.OptionalInt.empty(), "",
          java.util.OptionalInt.empty(), java.util.OptionalInt.empty(), java.util.OptionalLong.empty(), snap.lastProbe());
    }
    return switch (snap.health()) {
      case HEALTHY -> ad.map(a -> ServerStatus.online(name, a.protocol(), a.name(), a.onlinePlayers(), a.maxPlayers(), a.latencyMillis(), snap.lastProbe()))
          .orElse(ServerStatus.online(name, -1, "", -1, -1, -1, snap.lastProbe()));
      case UNHEALTHY -> ServerStatus.offline(name, snap.lastProbe());
      case UNKNOWN -> ServerStatus.unknown(name);
    };
  }

  @Override public void close() {
    if (!closed.compareAndSet(false, true)) return;
    stopScheduler();
  }

  private static final class State {
    private final String name;
    private BackendHealth health = BackendHealth.UNKNOWN;
    private int failures;
    private int successes;
    private Instant lastProbe = Instant.EPOCH;
    private boolean lastOk;
    private State(String name) { this.name = name; }
  }
}
