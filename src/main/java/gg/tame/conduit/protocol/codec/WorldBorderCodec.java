package gg.tame.conduit.protocol.codec;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.semantic.WorldBorderInitPacket;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Version-specific world border codecs.
 *
 * <p>Layouts derived from observed wire behaviour and public protocol documentation:
 * <ul>
 *   <li><b>Legacy (1.13 / 393)</b> — one multiplexed packet: {@code VarInt action} then the
 *       action body. Action {@code 3} is INITIALIZE: x, z, oldDiameter, newDiameter (doubles),
 *       speed (VarLong), portalTeleportBoundary (VarInt), <b>warningTime</b> (VarInt),
 *       <b>warningBlocks</b> (VarInt).</li>
 *   <li><b>Modern (1.17+ / 765)</b> — dedicated Initialize World Border packet with the same
 *       values but <b>warningBlocks</b> before <b>warningTime</b>.</li>
 * </ul>
 */
public final class WorldBorderCodec {
  /** Action id for INITIALIZE inside the legacy multiplexed world border packet. */
  private static final int LEGACY_ACTION_INITIALIZE = 3;

  /** Highest protocol number still using the multiplexed legacy world border packet layout. */
  private static final int LEGACY_MAX_PROTOCOL = 404;

  private WorldBorderCodec() {}

  private static boolean legacy(ProtocolDefinition protocol) {
    return protocol.version().number() <= LEGACY_MAX_PROTOCOL;
  }

  /** Decodes an initialize-world-border packet in the era of {@code protocol}. */
  public static WorldBorderInitPacket decodeInit(ProtocolDefinition protocol, PacketDirection direction,
                                                 byte[] packet) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(packet)))) {
      if (legacy(protocol)) {
        int action = MinecraftInput.varInt(input);
        if (action != LEGACY_ACTION_INITIALIZE) {
          throw new IOException("world border action " + action + " is not INITIALIZE");
        }
      }
      double centerX = input.readDouble();
      double centerZ = input.readDouble();
      double oldDiameter = input.readDouble();
      double newDiameter = input.readDouble();
      long speed = MinecraftInput.varLong(input);
      int portalBoundary = MinecraftInput.varInt(input);
      int warningBlocks;
      int warningTime;
      if (legacy(protocol)) {
        warningTime = MinecraftInput.varInt(input);
        warningBlocks = MinecraftInput.varInt(input);
      } else {
        warningBlocks = MinecraftInput.varInt(input);
        warningTime = MinecraftInput.varInt(input);
      }
      return new WorldBorderInitPacket(direction, centerX, centerZ, oldDiameter, newDiameter,
          speed, portalBoundary, warningBlocks, warningTime);
    }
  }

  /** Encodes an initialize-world-border packet in the era of {@code protocol}. */
  public static byte[] encodeInit(ProtocolDefinition protocol, WorldBorderInitPacket border) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream(48);
    try (DataOutputStream output = new DataOutputStream(buffer)) {
      if (legacy(protocol)) {
        MinecraftOutput.varInt(output, LEGACY_ACTION_INITIALIZE);
      }
      output.writeDouble(border.centerX());
      output.writeDouble(border.centerZ());
      output.writeDouble(border.oldDiameter());
      output.writeDouble(border.newDiameter());
      MinecraftOutput.varLong(output, border.speedMillis());
      MinecraftOutput.varInt(output, border.portalTeleportBoundary());
      if (legacy(protocol)) {
        MinecraftOutput.varInt(output, border.warningTime());
        MinecraftOutput.varInt(output, border.warningBlocks());
      } else {
        MinecraftOutput.varInt(output, border.warningBlocks());
        MinecraftOutput.varInt(output, border.warningTime());
      }
    }
    int id = protocol.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_WORLD_BORDER_INIT);
    return PlayPackets.withId(id, buffer.toByteArray());
  }
}
