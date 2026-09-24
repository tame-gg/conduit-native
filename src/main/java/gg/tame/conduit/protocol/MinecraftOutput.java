// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol;

import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class MinecraftOutput {
  private MinecraftOutput() { }
  public static void varInt(DataOutput output, int value) throws IOException {
    while ((value & ~0x7f) != 0) { output.writeByte((value & 0x7f) | 0x80); value >>>= 7; }
    output.writeByte(value);
  }
  public static void varLong(DataOutput output, long value) throws IOException {
    while ((value & ~0x7fL) != 0) { output.writeByte((int) (value & 0x7f) | 0x80); value >>>= 7; }
    output.writeByte((int) value);
  }
  public static void string(DataOutput output, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8); varInt(output, bytes.length); output.write(bytes);
  }
  public static void bytes(DataOutput output, byte[] value) throws IOException {
    varInt(output, value.length); output.write(value);
  }
  /** 1.7 login byte arrays: a big-endian short length instead of a VarInt. */
  public static void shortBytes(DataOutput output, byte[] value) throws IOException {
    output.writeShort(value.length); output.write(value);
  }
}
