// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.server;

import gg.tame.conduit.api.event.proxy.ServerListPingEvent;
import gg.tame.conduit.api.text.Text;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * A backend's status. Never exposes secrets or raw addresses to players.
 *
 * <p>The cached {@link RegisteredServer#status()} has availability, version and player counts only.
 * A live {@link RegisteredServer#ping()} also carries what the backend says about itself in the
 * server list: its {@code description} (MOTD), its {@code favicon} as a
 * {@code data:image/png;base64,} URI, and the {@code samplePlayers} it lists on hover, which are
 * whatever the backend chose to show and need not be real players. Each is empty when the backend
 * gave none. The description keeps what {@link Text} can carry: colours outside the sixteen named
 * ones, fonts and translation arguments are dropped, and legacy section-sign colour codes in a
 * plain-string description stay in its text. {@code modInfo} is the mod list a Forge server puts in
 * its answer ({@code modinfo}, {@code forgeData} or {@code neoForgeData}), empty for any other server.
 */
public record ServerStatus(
    String name,
    ServerAvailability availability,
    OptionalInt protocol,
    String versionName,
    OptionalInt onlinePlayers,
    OptionalInt maxPlayers,
    OptionalLong latencyMillis,
    Instant lastProbe,
    Text description,
    Optional<String> favicon,
    List<ServerListPingEvent.SamplePlayer> samplePlayers,
    Optional<ModInfo> modInfo
) {
  public ServerStatus {
    if (description == null) description = Text.empty();
    if (favicon == null) favicon = Optional.empty();
    samplePlayers = samplePlayers == null ? List.of() : List.copyOf(samplePlayers);
    if (modInfo == null) modInfo = Optional.empty();
  }
  /** A status with no mod list. */
  public ServerStatus(String name, ServerAvailability availability, OptionalInt protocol, String versionName,
                      OptionalInt onlinePlayers, OptionalInt maxPlayers, OptionalLong latencyMillis, Instant lastProbe,
                      Text description, Optional<String> favicon, List<ServerListPingEvent.SamplePlayer> samplePlayers) {
    this(name, availability, protocol, versionName, onlinePlayers, maxPlayers, latencyMillis, lastProbe, description, favicon, samplePlayers, Optional.empty());
  }
  /** A status without a description, favicon or player sample, as the cached one is. */
  public ServerStatus(String name, ServerAvailability availability, OptionalInt protocol, String versionName,
                      OptionalInt onlinePlayers, OptionalInt maxPlayers, OptionalLong latencyMillis, Instant lastProbe) {
    this(name, availability, protocol, versionName, onlinePlayers, maxPlayers, latencyMillis, lastProbe, Text.empty(), Optional.empty(), List.of(), Optional.empty());
  }
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
