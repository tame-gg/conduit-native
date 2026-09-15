package gg.tame.conduit.protocol.semantic;

import gg.tame.conduit.login.PlayerProfile;
import gg.tame.conduit.login.ProfileProperty;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import java.util.List;
import java.util.UUID;

/** Login Success independent of string/binary UUID and property layout. */
public record LoginSuccessPacket(
    PacketDirection direction,
    UUID uniqueId,
    String username,
    List<ProfileProperty> properties
) implements SemanticPacket {
  public LoginSuccessPacket {
    if (uniqueId == null) throw new IllegalArgumentException("uuid required");
    if (username == null || username.isBlank()) throw new IllegalArgumentException("username required");
    if (properties == null) properties = List.of();
    else properties = List.copyOf(properties);
  }

  public static LoginSuccessPacket fromProfile(PlayerProfile profile) {
    return new LoginSuccessPacket(PacketDirection.SERVER_TO_CLIENT, profile.uniqueId(), profile.username(), profile.properties());
  }

  @Override public PacketKind kind() { return PacketKind.LOGIN_SUCCESS; }
  @Override public ConnectionState state() { return ConnectionState.LOGIN; }
}
