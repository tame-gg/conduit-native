// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Bounded packet framing that preserves the original encoded packet payload. */
public final class MinecraftFrames {
  /** Large enough that an ordinary packet is still one allocation, small enough to be cheap. */
  private static final int INITIAL_FRAME_BYTES = 8192;
  private MinecraftFrames() {}
  public static byte[] read(InputStream source, int maximumFrameBytes) throws IOException {
    int length = readVarInt(source);
    if (length < 0 || length > maximumFrameBytes) throw new IOException("packet exceeds configured frame limit");
    // Grown to what arrives, not to what was announced. The length is the peer's word for it and
    // nothing has been read yet: with the limit at its maximum, every unauthenticated connection
    // could buy a full-size array by declaring one and then sending nothing.
    byte[] packet = new byte[Math.min(length, INITIAL_FRAME_BYTES)];
    int offset = 0;
    while (offset < length) {
      // Once the peer has actually delivered the first chunk, the rest is allocated in one piece:
      // it has paid for the frame it declared, and a big packet is read the way it always was.
      if (offset == packet.length) packet = java.util.Arrays.copyOf(packet, length);
      int n = source.read(packet, offset, packet.length - offset);
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
    // The length goes out in one write, not a byte at a time. Every byte written singly to an
    // encrypted connection is its own AES/CFB8 call, and the JCE call overhead there is most of
    // what a byte costs: a short packet spent as long on its two-byte length as on its body. The
    // array is small, local and never escapes, so it is the allocator's cheapest case.
    byte[] prefix = new byte[5];
    int length = 0;
    int value = packet.length;
    while ((value & ~0x7f) != 0) { prefix[length++] = (byte) ((value & 0x7f) | 0x80); value >>>= 7; }
    prefix[length++] = (byte) value;
    destination.write(prefix, 0, length);
    destination.write(packet);
  }
  static int readVarInt(InputStream source) throws IOException {
    int value = 0;
    for (int index = 0; index < 5; index++) {
      int current = source.read();
      if (current < 0) throw new EOFException("truncated VarInt");
      // A fifth byte carries four usable bits. Anything above them shifts off the end of the int,
      // so 80 80 80 80 10 read as a length of zero and the frame behind it was consumed as another
      // packet's body -- a stream desync from five bytes, with no error anywhere.
      if (index == 4 && (current & 0x70) != 0) throw new IOException("VarInt overflows an int");
      value |= (current & 0x7f) << (index * 7);
      if ((current & 0x80) == 0) return value;
    }
    throw new IOException("VarInt exceeds five bytes");
  }
}
