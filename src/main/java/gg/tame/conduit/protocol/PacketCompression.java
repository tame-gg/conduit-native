// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Minecraft login compression wrapper. Outer VarInt framing is unchanged. */
public final class PacketCompression {
  private static final int INFLATE_CHUNK_BYTES = 8192;
  private int threshold = -1;
  private final int maximumUncompressedBytes;
  private final Inflater inflater = new Inflater();
  /**
   * Deflate level for every packet this proxy re-compresses.
   *
   * <p>zlib's own default is 6, which is chosen for files written once and read many times. A proxy
   * is the other case: every packet is deflated once, travels once, and is thrown away. Level 4
   * reaches roughly the same size on the chunk and entity data that dominates the stream for a
   * fraction of the CPU, and the bytes saved by 6 are not worth the time spent finding them on a
   * connection that is re-compressing for every player on the network.
   */
  private static final int DEFLATE_LEVEL = 4;
  /** Where a deflate buffer starts; grown, and kept, for the next packet through this connection. */
  private static final int DEFLATE_CHUNK_BYTES = 8192;
  private final Deflater deflater = new Deflater(DEFLATE_LEVEL);
  /**
   * Held across packets rather than allocated per packet. {@code wrap} used to make a 512-byte
   * buffer, two {@link ByteArrayOutputStream}s and a {@code toByteArray} copy for every packet it
   * compressed, so a busy connection spent more time in the allocator than in zlib.
   */
  private byte[] deflateBuffer = new byte[DEFLATE_CHUNK_BYTES];
  private final Object inflateLock = new Object();
  private final Object deflateLock = new Object();
  public PacketCompression(int maximumUncompressedBytes) { this.maximumUncompressedBytes = maximumUncompressedBytes; }
  public boolean enabled() { return threshold >= 0; }
  public void enable(int threshold) { this.threshold = threshold; }
  public byte[] unwrap(byte[] framedPayload) throws IOException {
    if (!enabled()) return framedPayload;
    int index = 0;
    int uncompressedSize = 0;
    for (int shift = 0; shift < 5; shift++) {
      if (index >= framedPayload.length) throw new IOException("truncated compressed packet");
      int current = framedPayload[index++] & 0xff;
      // A fifth byte carries four usable bits; anything above them shifts off the end of the int.
      // 80 80 80 80 10 declared a size of zero, and the whole deflate stream behind it was handed
      // up as if it were an uncompressed packet.
      if (shift == 4 && (current & 0x70) != 0) throw new IOException("compressed size VarInt overflows an int");
      uncompressedSize |= (current & 0x7f) << (shift * 7);
      if ((current & 0x80) == 0) break;
      if (shift == 4) throw new IOException("VarInt exceeds five bytes");
    }
    if (uncompressedSize == 0) {
      byte[] body = new byte[framedPayload.length - index];
      System.arraycopy(framedPayload, index, body, 0, body.length);
      return body;
    }
    if (uncompressedSize < 0 || uncompressedSize > maximumUncompressedBytes) throw new IOException("compressed packet exceeds configured frame limit");
    synchronized (inflateLock) {
      inflater.reset();
      inflater.setInput(framedPayload, index, framedPayload.length - index);
      // Grown to what the stream actually produces rather than to what it claims. A peer that
      // declares the frame limit and sends twelve bytes used to cost one full-size allocation per
      // packet, which is the cheapest amplification there is.
      byte[] inflated = new byte[Math.min(uncompressedSize, INFLATE_CHUNK_BYTES)];
      try {
        int produced = 0;
        while (produced < uncompressedSize) {
          if (produced == inflated.length) {
            inflated = java.util.Arrays.copyOf(inflated, (int) Math.min(uncompressedSize, inflated.length * 2L));
          }
          int n = inflater.inflate(inflated, produced, inflated.length - produced);
          if (n == 0) {
            // Nothing produced and the stream is not finished: it has either run out of input or
            // is asking for a preset dictionary. Neither can make progress, and looping on the
            // second one spun a core at 100% forever on twenty-one bytes from the peer.
            if (inflater.finished()) break;
            if (inflater.needsInput()) throw new IOException("truncated compressed packet");
            throw new IOException("malformed compressed packet");
          }
          produced += n;
        }
        if (produced != uncompressedSize) throw new IOException("truncated compressed packet");
        // The declared size is the whole packet, so anything still to come is a packet that lied
        // about its length; forwarding the first N bytes of it desynchronises the reader instead.
        if (!inflater.finished() && inflater.inflate(new byte[1]) > 0) {
          throw new IOException("compressed packet is longer than it declared");
        }
      } catch (DataFormatException exception) { throw new IOException("malformed compressed packet", exception); }
      return inflated;
    }
  }
  public byte[] wrap(byte[] packet) throws IOException {
    if (!enabled()) return packet;
    if (packet.length < threshold) {
      // A zero size VarInt is one byte, and it means "what follows is not compressed". Below the
      // threshold that is most of the stream -- movement, keep alives, chat -- so it is built by
      // hand rather than through two streams and a copy.
      byte[] uncompressed = new byte[packet.length + 1];
      uncompressed[0] = 0;
      System.arraycopy(packet, 0, uncompressed, 1, packet.length);
      return uncompressed;
    }
    synchronized (deflateLock) {
      deflater.reset();
      deflater.setInput(packet);
      deflater.finish();
      // The size VarInt goes in front of the deflate stream, so deflate straight in behind it and
      // hand back one array. Nothing here copies the compressed bytes a second time.
      int prefix = varIntBytes(packet.length);
      if (deflateBuffer.length < prefix + DEFLATE_CHUNK_BYTES) {
        deflateBuffer = new byte[prefix + DEFLATE_CHUNK_BYTES];
      }
      writeVarInt(deflateBuffer, 0, packet.length);
      int produced = prefix;
      while (!deflater.finished()) {
        if (produced == deflateBuffer.length) {
          deflateBuffer = java.util.Arrays.copyOf(deflateBuffer, deflateBuffer.length * 2);
        }
        produced += deflater.deflate(deflateBuffer, produced, deflateBuffer.length - produced);
      }
      return java.util.Arrays.copyOf(deflateBuffer, produced);
    }
  }

  /** Bytes a VarInt of this value takes; it is never negative here, so five is the most. */
  private static int varIntBytes(int value) {
    int bytes = 1;
    while ((value & ~0x7f) != 0) { value >>>= 7; bytes++; }
    return bytes;
  }

  /** The same encoding {@link MinecraftOutput#varInt} writes, straight into an array. */
  private static int writeVarInt(byte[] destination, int offset, int value) {
    while ((value & ~0x7f) != 0) {
      destination[offset++] = (byte) ((value & 0x7f) | 0x80);
      value >>>= 7;
    }
    destination[offset++] = (byte) value;
    return offset;
  }
}
