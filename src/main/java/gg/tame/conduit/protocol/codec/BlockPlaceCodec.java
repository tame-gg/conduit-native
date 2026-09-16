package gg.tame.conduit.protocol.codec;

import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.ProtocolEras;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Player Block Placement body rematerialisation across position packing and
 * field-order changes.
 *
 * <pre>
 *   ≤404     location  face  hand  cursor×3
 *   477–758  hand  location  face  cursor×3  insideBlock
 *   ≥759     hand  location  face  cursor×3  insideBlock  sequence
 * </pre>
 */
public final class BlockPlaceCodec {
  private BlockPlaceCodec() {}

  public static byte[] translate(byte[] body, ProtocolDefinition source, ProtocolDefinition target)
      throws IOException {
    int from = source.version().number();
    int to = target.version().number();
    boolean fromHandFirst = from > ProtocolEras.LEGACY_POSITION_MAX;
    boolean toHandFirst = to > ProtocolEras.LEGACY_POSITION_MAX;
    boolean fromSequence = ProtocolEras.blockPlaceSequence(from);
    boolean toSequence = ProtocolEras.blockPlaceSequence(to);
    if (fromHandFirst == toHandFirst && fromSequence == toSequence
        && ProtocolEras.legacyPosition(from) == ProtocolEras.legacyPosition(to)) {
      return body;
    }
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream(body.length + 8);
      DataOutputStream out = new DataOutputStream(buffer);
      int hand;
      long position;
      int face;
      if (fromHandFirst) {
        hand = MinecraftInput.varInt(in);
        position = in.readLong();
        face = MinecraftInput.varInt(in);
      } else {
        position = in.readLong();
        face = MinecraftInput.varInt(in);
        hand = MinecraftInput.varInt(in);
      }
      long converted = BlockPositionCodec.translate(source, target, position);
      if (toHandFirst) {
        MinecraftOutput.varInt(out, hand);
        out.writeLong(converted);
        MinecraftOutput.varInt(out, face);
      } else {
        out.writeLong(converted);
        MinecraftOutput.varInt(out, face);
        MinecraftOutput.varInt(out, hand);
      }
      for (int index = 0; index < 3; index++) out.writeFloat(in.readFloat());
      if (fromHandFirst) {
        if (in.available() > 0) in.readBoolean(); // insideBlock
        if (fromSequence && in.available() > 0) MinecraftInput.varInt(in);
      }
      if (toHandFirst) {
        out.writeBoolean(false);
        if (toSequence) MinecraftOutput.varInt(out, 0);
      }
      out.flush();
      return buffer.toByteArray();
    }
  }
}
