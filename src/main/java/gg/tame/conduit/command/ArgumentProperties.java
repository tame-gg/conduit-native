// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.command;

import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** 1.20.1/1.20.4 argument-node property payloads keyed by numeric parser id. */
public final class ArgumentProperties {
  private ArgumentProperties() {}

  /**
   * The 1.19+ id for a pre-1.19 parser identifier, so one property reader serves both wire forms.
   *
   * <p>Only the parsers whose payload is not empty need an answer, and of those only the ones Conduit
   * writes or expects to meet. Anything else gets -1, which {@link #read} treats as a parser with no
   * properties -- the same thing it does with an id from a newer release it has never seen. That is
   * safe for Conduit's own trees, where the only argument is {@code brigadier:string}; a backend's
   * pre-1.19 tree is never decoded, only copied through byte for byte.
   */
  public static int idFor(String parserName) {
    if (parserName == null) return -1;
    return switch (parserName) {
      case "brigadier:float" -> 1;
      case "brigadier:double" -> 2;
      case "brigadier:integer" -> 3;
      case "brigadier:long" -> 4;
      case "brigadier:string" -> 5;
      case "minecraft:entity" -> 6;
      case "minecraft:score_holder" -> 29;
      default -> -1;
    };
  }

  public static byte[] read(DataInputStream input, int parserId) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream output = new DataOutputStream(bytes);
    switch (parserId) {
      case 1, 2, 3, 4 -> copyNumberRange(input, output, parserId);
      case 5 -> MinecraftOutput.varInt(output, MinecraftInput.varInt(input));
      case 6, 29 -> output.writeByte(input.readByte());
      case 40 -> output.writeInt(input.readInt());
      case 41, 42, 43, 44 -> MinecraftOutput.string(output, MinecraftInput.string(input, 32767));
      default -> { }
    }
    return bytes.toByteArray();
  }
  private static void copyNumberRange(DataInputStream input, DataOutputStream output, int parserId) throws IOException {
    int flags = input.readUnsignedByte();
    output.writeByte(flags);
    boolean min = (flags & 0x01) != 0;
    boolean max = (flags & 0x02) != 0;
    if (parserId == 1) { if (min) output.writeFloat(input.readFloat()); if (max) output.writeFloat(input.readFloat()); }
    else if (parserId == 2) { if (min) output.writeDouble(input.readDouble()); if (max) output.writeDouble(input.readDouble()); }
    else if (parserId == 3) { if (min) output.writeInt(input.readInt()); if (max) output.writeInt(input.readInt()); }
    else { if (min) output.writeLong(input.readLong()); if (max) output.writeLong(input.readLong()); }
  }
}
