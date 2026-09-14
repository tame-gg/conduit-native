package gg.tame.conduit.api.server;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/** Cached backend status. Never exposes secrets or raw addresses to players. */
public record ServerStatus(
    String name,
    ServerAvailability availability,
    OptionalInt protocol,
    String versionName,
    OptionalInt onlinePlayers,
    OptionalInt maxPlayers,
    OptionalLong latencyMillis,
    Instant lastProbe
) {
  public static ServerStatus online(
      String name,
      int protocol,
      String versionName,
      int onlinePlayers,
      int maxPlayers,
      long latencyMillis,
      Instant lastProbe
  ) {
    return new ServerStatus(
        name,
        ServerAvailability.ONLINE,
        OptionalInt.of(protocol),
        versionName == null ? "" : versionName,
        onlinePlayers >= 0 ? OptionalInt.of(onlinePlayers) : OptionalInt.empty(),
        maxPlayers >= 0 ? OptionalInt.of(maxPlayers) : OptionalInt.empty(),
        latencyMillis >= 0 ? OptionalLong.of(latencyMillis) : OptionalLong.empty(),
        lastProbe);
  }
  public static ServerStatus offline(String name, Instant lastProbe) {
    return new ServerStatus(name, ServerAvailability.OFFLINE, OptionalInt.empty(), "", OptionalInt.empty(), OptionalInt.empty(), OptionalLong.empty(), lastProbe);
  }
  public static ServerStatus unknown(String name) {
    return new ServerStatus(name, ServerAvailability.UNKNOWN, OptionalInt.empty(), "", OptionalInt.empty(), OptionalInt.empty(), OptionalLong.empty(), Instant.EPOCH);
  }
  public boolean online() { return availability == ServerAvailability.ONLINE; }
  public Optional<String> displayVersion() {
    return versionName == null || versionName.isBlank() ? Optional.empty() : Optional.of(versionName);
  }
}
