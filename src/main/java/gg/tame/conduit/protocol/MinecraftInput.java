package gg.tame.conduit.protocol;

import java.io.DataInput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Primitive Minecraft wire readers used only by packets Conduit understands. */
public final class MinecraftInput {
  private MinecraftInput() {}
  public static int varInt(DataInput input) throws IOException {
    int value = 0;
    for (int index = 0; index < 5; index++) {
      int current = input.readUnsignedByte();
      value |= (current & 0x7f) << (index * 7);
      if ((current & 0x80) == 0) return value;
    }
    throw new IOException("VarInt exceeds five bytes");
  }
  public static long varLong(DataInput input) throws IOException {
    long value = 0;
    for (int index = 0; index < 10; index++) {
      int current = input.readUnsignedByte();
      value |= (long) (current & 0x7f) << (index * 7);
      if ((current & 0x80) == 0) return value;
    }
    throw new IOException("VarLong exceeds ten bytes");
  }
  public static String string(DataInput input, int maximumBytes) throws IOException {
    int length = varInt(input);
    if (length < 0 || length > maximumBytes) throw new IOException("string length exceeds limit");
    byte[] bytes = new byte[length]; input.readFully(bytes);
    return new String(bytes, StandardCharsets.UTF_8);
  }
  public static byte[] bytes(DataInput input, int maximumBytes) throws IOException {
    int length = varInt(input);
    if (length < 0 || length > maximumBytes) throw new IOException("byte array length exceeds limit");
    byte[] bytes = new byte[length]; input.readFully(bytes); return bytes;
  }
}
