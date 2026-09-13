package gg.tame.conduit.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Minecraft 1.20.4 login compression wrapper. Outer VarInt framing is unchanged. */
public final class PacketCompression {
  private int threshold = -1;
  private final int maximumUncompressedBytes;
  public PacketCompression(int maximumUncompressedBytes) { this.maximumUncompressedBytes = maximumUncompressedBytes; }
  public boolean enabled() { return threshold >= 0; }
  public void enable(int threshold) { this.threshold = threshold; }
  public byte[] unwrap(byte[] framedPayload) throws IOException {
    if (!enabled()) return framedPayload;
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(framedPayload))) {
      int uncompressedSize = MinecraftInput.varInt(input);
      byte[] body = input.readAllBytes();
      if (uncompressedSize == 0) return body;
      if (uncompressedSize < 0 || uncompressedSize > maximumUncompressedBytes) throw new IOException("compressed packet exceeds configured frame limit");
      Inflater inflater = new Inflater();
      inflater.setInput(body);
      byte[] inflated = new byte[uncompressedSize];
      try {
        int produced = inflater.inflate(inflated);
        if (produced != uncompressedSize || !inflater.finished()) throw new IOException("truncated compressed packet");
      } catch (DataFormatException exception) { throw new IOException("malformed compressed packet", exception); }
      finally { inflater.end(); }
      return inflated;
    }
  }
  public byte[] wrap(byte[] packet) throws IOException {
    if (!enabled()) return packet;
    if (packet.length < threshold) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) { MinecraftOutput.varInt(output, 0); output.write(packet); }
      return bytes.toByteArray();
    }
    Deflater deflater = new Deflater();
    deflater.setInput(packet);
    deflater.finish();
    ByteArrayOutputStream compressed = new ByteArrayOutputStream();
    byte[] buffer = new byte[512];
    while (!deflater.finished()) { int n = deflater.deflate(buffer); compressed.write(buffer, 0, n); }
    deflater.end();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) { MinecraftOutput.varInt(output, packet.length); compressed.writeTo(output); }
    return bytes.toByteArray();
  }
}
