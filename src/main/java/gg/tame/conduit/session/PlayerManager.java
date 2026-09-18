// SPDX-License-Identifier: GPL-3.0-or-later
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
  /**
   * Who holds each identity, from the login that claimed it until that session's PlayerDisconnectEvent
   * has fired: the index above only knows players who reached a server, so two logins of one player
   * could both get past it and both be let in. The name counts as well as the UUID, because offline
   * mode derives the UUID from the name case by case while every lookup by name ignores case. Guarded
   * by this.
   */
  private final java.util.Map<UUID, PlayerSession> claimedIds = new java.util.HashMap<>();
  private final java.util.Map<String, PlayerSession> claimedNames = new java.util.HashMap<>();
  /**
   * Claims {@code session}'s identities; null when it now holds them all, else a session holding one.
   * Both the account that logged in and the profile a plugin replaced it with count: the account, so
   * one account is one session whatever a plugin calls it, and the replacement, so no two sessions are
   * shown to plugins and backends under one UUID or name.
   */
  public synchronized PlayerSession claim(PlayerSession session) {
    for (UUID id : ids(session)) {
      PlayerSession holder = claimedIds.get(id);
      if (holder != null && holder != session) return holder;
    }
    for (String name : names(session)) {
      PlayerSession holder = claimedNames.get(name);
      if (holder != null && holder != session) return holder;
    }
    for (UUID id : ids(session)) claimedIds.put(id, session);
    for (String name : names(session)) claimedNames.put(name, session);
    return null;
  }
  public synchronized void release(PlayerSession session) {
    boolean held = false;
    for (UUID id : ids(session)) held |= claimedIds.remove(id, session);
    for (String name : names(session)) held |= claimedNames.remove(name, session);
    if (held) notifyAll();
  }
  private static List<UUID> ids(PlayerSession session) { return List.of(session.uniqueId(), session.accountProfile().uniqueId()); }
  private static List<String> names(PlayerSession session) {
    return List.of(session.username().toLowerCase(Locale.ROOT), session.accountProfile().username().toLowerCase(Locale.ROOT));
  }
  private boolean holds(PlayerSession holder) {
    return ids(holder).stream().anyMatch(id -> claimedIds.get(id) == holder)
        || names(holder).stream().anyMatch(name -> claimedNames.get(name) == holder);
  }
  /** Waits up to {@code millis} for {@code holder} to release its claim; true once it has. */
  public synchronized boolean awaitRelease(PlayerSession holder, long millis) throws InterruptedException {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis);
    while (holds(holder)) {
      long left = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
      if (left <= 0) return false;
      wait(left);
    }
    return true;
  }
  public void add(TrackedPlayer session) {
    UUID id = session.uniqueId();
    byId.put(id, session);
    byName.put(session.username().toLowerCase(Locale.ROOT), id);
  }
  /**
   * A player who reconnects before the old session notices is indexed twice under one UUID. The
   * name index is keyed by UUID, so removing the dead session matched it and took the live
   * session's name with it: {@code /find} and every lookup by name reported a player offline while
   * they were standing in a world. Only the session that still owns the UUID owns the name.
   */
  public void remove(TrackedPlayer session) {
    UUID id = session.uniqueId();
    if (byId.remove(id, session)) byName.remove(session.username().toLowerCase(Locale.ROOT), id);
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
