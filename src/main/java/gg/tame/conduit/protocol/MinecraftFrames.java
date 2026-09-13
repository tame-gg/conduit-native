package gg.tame.conduit.protocol;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Bounded packet framing that preserves the original encoded packet payload. */
public final class MinecraftFrames {
  private MinecraftFrames() {}
  public static byte[] read(InputStream source, int maximumFrameBytes) throws IOException {
    DataInputStream input = new DataInputStream(source);
    int length = MinecraftInput.varInt(input);
    if (length < 0 || length > maximumFrameBytes) throw new IOException("packet exceeds configured frame limit");
    byte[] packet = new byte[length]; input.readFully(packet); return packet;
  }
  public static void write(OutputStream destination, byte[] packet) throws IOException {
    int value = packet.length;
    while ((value & ~0x7f) != 0) { destination.write((value & 0x7f) | 0x80); value >>>= 7; }
    destination.write(value); destination.write(packet); destination.flush();
  }
}
