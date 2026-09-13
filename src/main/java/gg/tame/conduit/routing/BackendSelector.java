package gg.tame.conduit.routing;

import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import java.util.List;

/** Selects configured initial and fallback backends without transport coupling. */
public final class BackendSelector {
  private final ConduitConfiguration configuration;
  public BackendSelector(ConduitConfiguration configuration) { this.configuration = configuration; }
  public List<BackendServer> candidates() {
    java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>(configuration.initialBackends());
    names.addAll(configuration.fallbackBackends());
    return names.stream().map(this::find).toList();
  }
  private BackendServer find(String name) { return configuration.backends().stream().filter(server -> server.name().equals(name)).findFirst().orElseThrow(); }
}
