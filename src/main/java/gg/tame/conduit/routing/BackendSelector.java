package gg.tame.conduit.routing;

import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.protocol.BackendStatusProbe;
import gg.tame.conduit.protocol.ProtocolCompatibility;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.TranslationSupport;
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
  public BackendSelector(ConduitConfiguration configuration) {
    this.configuration = configuration;
    this.registry = new ServerRegistry(configuration);
  }
  public ServerRegistry registry() { return registry; }
  public void probeAll() {
    for (BackendServer server : registry.all()) {
      Optional<BackendStatusProbe.Advertisement> advertisement = BackendStatusProbe.probe(server.address(), 2000);
      if (advertisement.isEmpty()) {
        System.out.println("Backend " + server.name() + " did not answer a status ping.");
        continue;
      }
      advertisements.put(ServerRegistry.normalize(server.name()), advertisement.get());
      System.out.println("Backend " + server.name() + ": " + BackendStatusProbe.describe(advertisement.get()));
      int protocol = advertisement.get().protocol();
      if (!ProtocolDefinition.hasCodec(protocol)) {
        System.out.println("Backend " + server.name() + " is running protocol " + protocol + ", which Conduit does not yet support.");
      }
    }
  }
  public Optional<BackendStatusProbe.Advertisement> advertisement(String name) {
    return Optional.ofNullable(advertisements.get(ServerRegistry.normalize(name)));
  }
  public TranslationSupport compatibility(int clientProtocol, String backendName) {
    return advertisement(backendName)
        .map(advertisement -> ProtocolCompatibility.between(clientProtocol, advertisement.protocol()))
        .orElse(ProtocolCompatibility.between(clientProtocol, clientProtocol));
  }
  public List<BackendServer> candidates() { return named(configuration.initialBackends(), configuration.fallbackBackends()); }
  public List<BackendServer> candidatesFor(int clientProtocol) {
    List<BackendServer> preferred = new ArrayList<>();
    List<BackendServer> unknown = new ArrayList<>();
    for (BackendServer server : candidates()) {
      var advertisement = advertisement(server.name());
      if (advertisement.isEmpty()) unknown.add(server);
      else if (ProtocolCompatibility.between(clientProtocol, advertisement.get().protocol()) == TranslationSupport.DIRECT) preferred.add(server);
    }
    for (BackendServer server : registry.all()) {
      if (preferred.contains(server) || unknown.contains(server)) continue;
      var advertisement = advertisement(server.name());
      if (advertisement.isEmpty()) continue;
      if (ProtocolCompatibility.between(clientProtocol, advertisement.get().protocol()) == TranslationSupport.DIRECT) {
        preferred.add(server);
      } else if (ProtocolDefinition.hasCodec(clientProtocol)) {
        unknown.add(server);
      }
    }
    List<BackendServer> ordered = new ArrayList<>(preferred);
    for (BackendServer server : candidates()) if (!ordered.contains(server)) ordered.add(server);
    for (BackendServer server : unknown) if (!ordered.contains(server)) ordered.add(server);
    return ordered.isEmpty() ? candidates() : ordered;
  }
  public List<BackendServer> fallback(String current, Set<String> failed) {
    List<String> names = new ArrayList<>(configuration.fallbackBackends());
    names.removeIf(name -> ServerRegistry.normalize(name).equals(ServerRegistry.normalize(current)));
    names.removeIf(name -> failed.contains(ServerRegistry.normalize(name)));
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
