// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol.semantic;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;

/** Serverbound movement. */
public record MovementPacket(
    PacketKind kind,
    PacketDirection direction,
    double x,
    double y,
    double z,
    float yaw,
    float pitch,
    boolean onGround,
    boolean hasPosition,
    boolean hasRotation
) implements SemanticPacket {
  @Override public ConnectionState state() { return ConnectionState.PLAY; }

  public static MovementPacket flying(boolean onGround) {
    return new MovementPacket(PacketKind.PLAY_FLYING, PacketDirection.CLIENT_TO_SERVER,
        0, 0, 0, 0, 0, onGround, false, false);
  }

  public static MovementPacket position(double x, double y, double z, boolean onGround) {
    return new MovementPacket(PacketKind.PLAY_POSITION, PacketDirection.CLIENT_TO_SERVER,
        x, y, z, 0, 0, onGround, true, false);
  }

  public static MovementPacket look(float yaw, float pitch, boolean onGround) {
    return new MovementPacket(PacketKind.PLAY_LOOK, PacketDirection.CLIENT_TO_SERVER,
        0, 0, 0, yaw, pitch, onGround, false, true);
  }

  public static MovementPacket positionLook(double x, double y, double z, float yaw, float pitch, boolean onGround) {
    return new MovementPacket(PacketKind.PLAY_POSITION_LOOK, PacketDirection.CLIENT_TO_SERVER,
        x, y, z, yaw, pitch, onGround, true, true);
  }
}
