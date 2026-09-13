package gg.tame.conduit.protocol;

import java.io.DataOutput;
import java.io.IOException;

/** Minimal network NBT writer for 1.20.4 text components. */
public final class NetworkNbt {
  private NetworkNbt() {}
  public static void stringComponent(DataOutput output, String text) throws IOException {
    output.writeByte(10);
    output.writeByte(8);
    output.writeUTF("text");
    output.writeUTF(text);
    output.writeByte(0);
  }
}
