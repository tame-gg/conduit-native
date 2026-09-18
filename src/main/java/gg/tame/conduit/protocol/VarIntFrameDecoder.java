// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol;

import java.nio.ByteBuffer;
import java.util.Optional;

/** Decodes length-prefixed frames without allocating until the declared length is bounded. */
public final class VarIntFrameDecoder {
  private final int maximumFrameBytes;
  public VarIntFrameDecoder(int maximumFrameBytes) { this.maximumFrameBytes = maximumFrameBytes; }
  public Optional<byte[]> tryDecode(ByteBuffer source) {
    source.mark();
    int length = 0;
    for (int index = 0; index < 5; index++) {
      if (!source.hasRemaining()) { source.reset(); return Optional.empty(); }
      int current = Byte.toUnsignedInt(source.get());
      // The fifth byte's high bits shift off the end of the int: 80 80 80 80 10 is not a frame of
      // zero bytes, it is a length that was never representable.
      if (index == 4 && (current & 0x70) != 0) throw new IllegalArgumentException("frame length VarInt overflows an int");
      length |= (current & 0x7f) << (7 * index);
      if ((current & 0x80) == 0) {
        if (length < 0 || length > maximumFrameBytes) throw new IllegalArgumentException("frame length exceeds configured limit");
        if (source.remaining() < length) { source.reset(); return Optional.empty(); }
        byte[] frame = new byte[length]; source.get(frame); return Optional.of(frame);
      }
    }
    throw new IllegalArgumentException("frame length VarInt exceeds five bytes");
  }
}
