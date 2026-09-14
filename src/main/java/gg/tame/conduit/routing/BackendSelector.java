package gg.tame.conduit.routing;

import gg.tame.conduit.api.server.ServerAvailability;
import gg.tame.conduit.api.server.ServerStatus;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.protocol.BackendStatusProbe;
import gg.tame.conduit.protocol.ProtocolCompatibility;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.TranslationSupport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Selects configured initial and fallback backends without transport coupling. */
public final class BackendSelector {
  private final ConduitConfiguration configuration;
  private final ServerRegistry registry;
  private final ConcurrentHashMap<String, BackendStatusProbe.Advertisement> advertisements = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, ServerStatus> statuses = new ConcurrentHashMap<>();
  private volatile Instant lastRefresh = Instant.EPOCH;

  public BackendSelector(ConduitConfiguration configuration) {
    this.configuration = configuration;
    this.registry = new ServerRegistry(configuration);
    for (BackendServer server : registry.all()) {
      statuses.put(ServerRegistry.normalize(server.name()), ServerStatus.unknown(server.name()));
    }
  }

  public ServerRegistry registry() { return registry; }
  public Instant lastRefresh() { return lastRefresh; }

  public void probeAll() { refreshStatus(true); }

  /** Quiet refresh used by the scheduler — no stdout spam. */
  public void refreshStatusQuietly() { refreshStatus(false); }

  private void refreshStatus(boolean log) {
    Instant now = Instant.now();
    for (BackendServer server : registry.all()) {
      String key = ServerRegistry.normalize(server.name());
      Optional<BackendStatusProbe.Advertisement> advertisement = BackendStatusProbe.probe(server.address(), 1500);
      if (advertisement.isEmpty()) {
        advertisements.remove(key);
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
    return Optional.ofNullable(advertisements.get(ServerRegistry.normalize(name)));
  }

  public ServerStatus status(String name) {
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

  public List<BackendServer> candidates() { return named(configuration.initialBackends(), configuration.fallbackBackends()); }

  /** First hop follows routing.initial, then fallback, then any other configured servers. Protocol advertisements never reorder that list. */
  public List<BackendServer> candidatesFor(int clientProtocol) {
    List<BackendServer> ordered = new ArrayList<>(candidates());
    for (BackendServer server : registry.all()) {
      if (ordered.contains(server)) continue;
      var advertisement = advertisement(server.name());
      if (advertisement.isEmpty()) {
        ordered.add(server);
        continue;
      }
      if (ProtocolCompatibility.between(clientProtocol, advertisement.get().protocol()) == TranslationSupport.DIRECT
          || ProtocolDefinition.hasCodec(clientProtocol)) {
        ordered.add(server);
      }
    }
    return ordered.isEmpty() ? candidates() : ordered;
  }

  public List<BackendServer> fallback(String current, Set<String> failed) {
    List<String> names = new ArrayList<>(configuration.fallbackBackends());
    names.removeIf(name -> ServerRegistry.normalize(name).equals(ServerRegistry.normalize(current)));
    names.removeIf(name -> failed.contains(ServerRegistry.normalize(name)));
    // Prefer known-online fallbacks when status is available.
    names.sort((a, b) -> Boolean.compare(status(b).online(), status(a).online()));
    return named(names);
  }

  private List<BackendServer> named(List<String> first, List<String> extra) {
    LinkedHashSet<String> names = new LinkedHashSet<>(first);
    names.addAll(extra);
    return named(List.copyOf(names));
  }

  private List<BackendServer> named(List<String> names) {
    List<BackendServer> result = new ArrayList<>();
    for (String name : names) result.add(registry.get(name).orElseThrow());
    return result;
  }
}
