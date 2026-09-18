// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol.semantic;

import gg.tame.conduit.login.ProfileProperty;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Cross-version player list entry. */
public record SemanticPlayerInfo(
    PacketDirection direction,
    Action action,
    List<Entry> entries
) implements SemanticPacket {
  public SemanticPlayerInfo {
    if (action == null) throw new IllegalArgumentException("action required");
    if (entries == null) entries = List.of();
    else entries = List.copyOf(entries);
  }

  @Override public PacketKind kind() {
    return action == Action.REMOVE_PLAYER ? PacketKind.PLAY_PLAYER_INFO_REMOVE : PacketKind.PLAY_PLAYER_INFO_UPDATE;
  }

  @Override public ConnectionState state() { return ConnectionState.PLAY; }

  public enum Action {
    ADD_PLAYER,
    UPDATE_GAME_MODE,
    UPDATE_LATENCY,
    UPDATE_DISPLAY_NAME,
    REMOVE_PLAYER,
    UPDATE_LISTED
  }

  public record Entry(
      UUID uuid,
      Optional<String> username,
      List<ProfileProperty> properties,
      Optional<Integer> gameMode,
      Optional<Integer> latency,
      Optional<String> displayNameJson,
      Optional<Boolean> listed
  ) {
    public Entry {
      if (uuid == null) throw new IllegalArgumentException("uuid");
      if (username == null) username = Optional.empty();
      if (properties == null) properties = List.of();
      else properties = List.copyOf(properties);
      if (gameMode == null) gameMode = Optional.empty();
      if (latency == null) latency = Optional.empty();
      if (displayNameJson == null) displayNameJson = Optional.empty();
      if (listed == null) listed = Optional.empty();
    }
  }
}
