// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
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
  private final Deflater deflater = new Deflater();
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
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(packet.length + 1);
      try (DataOutputStream output = new DataOutputStream(bytes)) { MinecraftOutput.varInt(output, 0); output.write(packet); }
      return bytes.toByteArray();
    }
    synchronized (deflateLock) {
      deflater.reset();
      deflater.setInput(packet);
      deflater.finish();
      ByteArrayOutputStream compressed = new ByteArrayOutputStream();
      byte[] buffer = new byte[512];
      while (!deflater.finished()) { int n = deflater.deflate(buffer); compressed.write(buffer, 0, n); }
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) { MinecraftOutput.varInt(output, packet.length); compressed.writeTo(output); }
      return bytes.toByteArray();
    }
  }
}
