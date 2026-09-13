package gg.tame.conduit.session;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Authoritative live player index. Server membership is read from each session. */
public final class PlayerManager {
  private final ConcurrentHashMap<UUID, TrackedPlayer> byId = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, UUID> byName = new ConcurrentHashMap<>();
  public void add(TrackedPlayer session) {
    UUID id = session.uniqueId();
    byId.put(id, session);
    byName.put(session.username().toLowerCase(Locale.ROOT), id);
  }
  public void remove(TrackedPlayer session) {
    UUID id = session.uniqueId();
    byId.remove(id, session);
    byName.remove(session.username().toLowerCase(Locale.ROOT), id);
  }
  public Optional<TrackedPlayer> get(UUID uniqueId) { return Optional.ofNullable(byId.get(uniqueId)); }
  public Optional<TrackedPlayer> getByUsername(String username) {
    UUID id = byName.get(username.toLowerCase(Locale.ROOT));
    return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
  }
  public List<TrackedPlayer> all() { return List.copyOf(byId.values()); }
  public List<TrackedPlayer> byServer(String server) {
    String needle = normalize(server);
    List<TrackedPlayer> result = new ArrayList<>();
    for (TrackedPlayer session : byId.values()) {
      if (needle.equals(normalize(session.currentBackend()))) result.add(session);
    }
    return result;
  }
  public List<String> onlineUsernames() {
    Collection<TrackedPlayer> sessions = byId.values();
    List<String> names = new ArrayList<>(sessions.size());
    for (TrackedPlayer session : sessions) names.add(session.username());
    return names;
  }
  private static String normalize(String name) { return name == null ? "" : name.trim().toLowerCase(Locale.ROOT); }
}
