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
