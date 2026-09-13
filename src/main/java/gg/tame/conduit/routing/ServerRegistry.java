package gg.tame.conduit.routing;

import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Configured backend names only. Addresses stay private. */
public final class ServerRegistry {
  private final Map<String, BackendServer> byNormalized = new LinkedHashMap<>();
  private final List<String> names = new ArrayList<>();
  public ServerRegistry(ConduitConfiguration configuration) {
    for (BackendServer server : configuration.backends()) {
      String key = normalize(server.name());
      if (byNormalized.putIfAbsent(key, server) != null) throw new IllegalArgumentException("duplicate backend name: " + server.name());
      names.add(server.name());
    }
  }
  public static String normalize(String name) { return name.trim().toLowerCase(Locale.ROOT); }
  public Optional<BackendServer> get(String name) { return Optional.ofNullable(byNormalized.get(normalize(name))); }
  public boolean contains(String name) { return get(name).isPresent(); }
  public List<BackendServer> all() { return List.copyOf(byNormalized.values()); }
  public List<String> names() { return List.copyOf(names); }
  public ServerMatch resolve(String query) {
    if (query == null || query.isBlank()) return ServerMatch.none();
    String needle = normalize(query);
    List<BackendServer> exact = new ArrayList<>();
    List<BackendServer> prefix = new ArrayList<>();
    for (BackendServer server : byNormalized.values()) {
      String name = normalize(server.name());
      if (name.equals(needle)) exact.add(server);
      else if (name.startsWith(needle)) prefix.add(server);
    }
    if (exact.size() == 1) return ServerMatch.unique(exact.getFirst());
    if (exact.size() > 1) return ServerMatch.ambiguous(namesOf(exact));
    if (prefix.size() == 1) return ServerMatch.unique(prefix.getFirst());
    if (prefix.size() > 1) return ServerMatch.ambiguous(namesOf(prefix));
    return ServerMatch.none();
  }
  private static List<String> namesOf(List<BackendServer> servers) { return servers.stream().map(BackendServer::name).toList(); }
}
