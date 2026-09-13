package gg.tame.conduit.routing;

import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Selects configured initial and fallback backends without transport coupling. */
public final class BackendSelector {
  private final ConduitConfiguration configuration;
  private final ServerRegistry registry;
  public BackendSelector(ConduitConfiguration configuration) {
    this.configuration = configuration;
    this.registry = new ServerRegistry(configuration);
  }
  public ServerRegistry registry() { return registry; }
  public List<BackendServer> candidates() { return named(configuration.initialBackends(), configuration.fallbackBackends()); }
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
