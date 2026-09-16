package gg.tame.conduit.protocol;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/** Minimal network NBT writer/skipper for text components. */
public final class NetworkNbt {
  private NetworkNbt() {}
  public static void stringComponent(DataOutput output, String text) throws IOException {
    output.writeByte(10);
    output.writeByte(8);
    output.writeUTF("text");
    output.writeUTF(text);
    output.writeByte(0);
  }
  public static void skip(DataInput input) throws IOException { skipPayload(input, input.readUnsignedByte()); }

  /**
   * Skips a named (disk-style) NBT value: type, modified UTF-8 name, payload.
   * 1.13 chunk block-entity lists use this form; calling {@link #skip} on them
   * treats the name length as the start of a nameless payload and desyncs the
   * rest of the chunk packet.
   */
  public static void skipNamed(DataInput input) throws IOException {
    int type = input.readUnsignedByte();
    if (type == 0) return;
    int nameLength = input.readUnsignedShort();
    if (nameLength < 0 || nameLength > 65535) throw new IOException("nbt name length");
    input.skipBytes(nameLength);
    skipPayload(input, type);
  }
  public static void copy(DataInput input, DataOutput output) throws IOException {
    int type = input.readUnsignedByte();
    output.writeByte(type);
    copyPayload(input, output, type);
  }

  /** Copies a named (disk-style) NBT value: type, modified UTF-8 name, payload. */
  public static void copyNamed(DataInput input, DataOutput output) throws IOException {
    int type = input.readUnsignedByte();
    output.writeByte(type);
    if (type == 0) return;
    int nameLength = input.readUnsignedShort();
    output.writeShort(nameLength);
    if (nameLength < 0 || nameLength > 65535) throw new IOException("nbt name length");
    copyBytes(input, output, nameLength);
    copyPayload(input, output, type);
  }

  private static void skipPayload(DataInput input, int type) throws IOException { copyPayload(input, null, type); }
  private static void copyPayload(DataInput input, DataOutput output, int type) throws IOException {
    switch (type) {
      case 0 -> { }
      case 1 -> copyBytes(input, output, 1);
      case 2 -> copyBytes(input, output, 2);
      case 3, 5 -> copyBytes(input, output, 4);
      case 4, 6 -> copyBytes(input, output, 8);
      case 7 -> { int n = input.readInt(); writeInt(output, n); if (n < 0 || n > 1_048_576) throw new IOException("nbt byte array"); copyBytes(input, output, n); }
      case 8 -> { int n = input.readUnsignedShort(); writeShort(output, n); copyBytes(input, output, n); }
      case 9 -> {
        int element = input.readUnsignedByte();
        if (output != null) output.writeByte(element);
        int length = input.readInt();
        writeInt(output, length);
        if (length < 0 || length > 65536) throw new IOException("nbt list");
        for (int index = 0; index < length; index++) copyPayload(input, output, element);
      }
      case 10 -> {
        while (true) {
          int child = input.readUnsignedByte();
          if (output != null) output.writeByte(child);
          if (child == 0) return;
          int name = input.readUnsignedShort();
          writeShort(output, name);
          copyBytes(input, output, name);
          copyPayload(input, output, child);
        }
      }
      case 11 -> { int n = input.readInt(); writeInt(output, n); if (n < 0 || n > 262144) throw new IOException("nbt int array"); copyBytes(input, output, n * 4); }
      case 12 -> { int n = input.readInt(); writeInt(output, n); if (n < 0 || n > 131072) throw new IOException("nbt long array"); copyBytes(input, output, n * 8); }
      default -> throw new IOException("unknown nbt type " + type);
    }
  }
  private static void copyBytes(DataInput input, DataOutput output, int length) throws IOException {
    byte[] data = new byte[length];
    input.readFully(data);
    if (output != null) output.write(data);
  }
  private static void writeInt(DataOutput output, int value) throws IOException { if (output != null) output.writeInt(value); }
  private static void writeShort(DataOutput output, int value) throws IOException { if (output != null) output.writeShort(value); }
}
