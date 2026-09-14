package gg.tame.conduit.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Bounded packet framing that preserves the original encoded packet payload. */
public final class MinecraftFrames {
  private MinecraftFrames() {}
  public static byte[] read(InputStream source, int maximumFrameBytes) throws IOException {
    int length = readVarInt(source);
    if (length < 0 || length > maximumFrameBytes) throw new IOException("packet exceeds configured frame limit");
    byte[] packet = new byte[length];
    int offset = 0;
    while (offset < length) {
      int n = source.read(packet, offset, length - offset);
      if (n < 0) throw new EOFException("truncated Minecraft frame");
      offset += n;
    }
    return packet;
  }
  public static void write(OutputStream destination, byte[] packet) throws IOException {
    writeUnflushed(destination, packet);
    destination.flush();
  }
  public static void writeUnflushed(OutputStream destination, byte[] packet) throws IOException {
    int value = packet.length;
    while ((value & ~0x7f) != 0) { destination.write((value & 0x7f) | 0x80); value >>>= 7; }
    destination.write(value);
    destination.write(packet);
  }
  static int readVarInt(InputStream source) throws IOException {
    int value = 0;
    for (int index = 0; index < 5; index++) {
      int current = source.read();
      if (current < 0) throw new EOFException("truncated VarInt");
      value |= (current & 0x7f) << (index * 7);
      if ((current & 0x80) == 0) return value;
    }
    throw new IOException("VarInt exceeds five bytes");
  }
}
