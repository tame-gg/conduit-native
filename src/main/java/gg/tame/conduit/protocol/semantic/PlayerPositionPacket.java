package gg.tame.conduit.protocol.semantic;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;

/** Clientbound synchronize player position (teleport). */
public record PlayerPositionPacket(
    PacketDirection direction,
    double x,
    double y,
    double z,
    float yaw,
    float pitch,
    byte flags,
    int teleportId
) implements SemanticPacket {
  @Override public PacketKind kind() { return PacketKind.PLAY_PLAYER_POSITION; }
  @Override public ConnectionState state() { return ConnectionState.PLAY; }
}
