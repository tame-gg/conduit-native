// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol.item;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reading and writing the item NBT compound in the one shape both eras share.
 *
 * <p>The "body" form used throughout is the compound's contents only: repeated
 * {@code type, name, payload} entries terminated by a TAG_End byte. 1.13 writes
 * an item's root compound with a name in front of it; 1.20.2 and later write the
 * root nameless. Converting between the two is therefore a matter of adding or
 * removing that name — the entries inside are identical, which is why item NBT
 * (Damage, display.Name, Enchantments, custom plugin tags) crosses this pair
 * intact instead of needing a field-by-field translation.
 */
public final class ItemNbt {
  /** Maximum accepted compound body; a real item stack is orders of magnitude smaller. */
  private static final int MAX_BODY = 512 * 1024;

  private ItemNbt() {}

  public record Enchantment(String identifier, int level) {}

  /**
   * Reads an item's NBT from the wire and returns the compound body.
   *
   * @param named true for 1.13-era roots, which carry a (usually empty) name
   * @return the compound body, or an empty array when the item has no NBT
   */
  public static byte[] readTag(DataInput input, boolean named) throws IOException {
    int type = input.readUnsignedByte();
    if (type == 0) return new byte[0];          // TAG_End: no NBT on this stack
    if (type != 10) throw new IOException("item nbt root is not a compound (type " + type + ")");
    if (named) skipName(input);
    ByteArrayOutputStream body = new ByteArrayOutputStream(64);
    copyCompoundBody(input, new DataOutputStream(body));
    if (body.size() > MAX_BODY) throw new IOException("item nbt body too large");
    return body.toByteArray();
  }

  /** Writes a compound body back out in the target era's root convention. */
  public static void writeTag(DataOutput output, byte[] body, boolean named) throws IOException {
    if (body.length == 0) {
      output.writeByte(0);                      // TAG_End: no NBT
      return;
    }
    output.writeByte(10);
    if (named) output.writeShort(0);            // 1.13 root name: empty string
    output.write(body);
  }

  // ------------------------------------------------------------------ queries

  /** An int-valued entry at the top level of the compound. */
  public static Optional<Integer> intValue(byte[] body, String key) {
    return find(body, key, 3, input -> input.readInt());
  }

  /** A string-valued entry at the top level of the compound. */
  public static Optional<String> stringValue(byte[] body, String key) {
    return find(body, key, 8, DataInput::readUTF);
  }

  /** The custom display name, stored by both eras as {@code display: {Name: "<json>"}}. */
  public static Optional<String> displayName(byte[] body) {
    return find(body, "display", 10, input -> {
      ByteArrayOutputStream nested = new ByteArrayOutputStream();
      copyCompoundBody(input, new DataOutputStream(nested));
      return stringValue(nested.toByteArray(), "Name").orElse(null);
    });
  }

  /**
   * Enchantments on the stack.
   *
   * <p>1.13 is the release that replaced numeric enchantment ids with
   * identifiers under the {@code Enchantments} key, so 1.13 and 1.20.4 already
   * agree here and the list crosses without translation. The pre-1.13
   * {@code ench} key is not read: an item carrying it did not come from either
   * side of this pair.
   */
  public static List<Enchantment> enchantments(byte[] body) {
    return find(body, "Enchantments", 9, input -> {
      int element = input.readUnsignedByte();
      int length = input.readInt();
      List<Enchantment> found = new ArrayList<>();
      if (element != 10 || length <= 0 || length > 4096) return found;
      for (int index = 0; index < length; index++) {
        ByteArrayOutputStream entry = new ByteArrayOutputStream();
        copyCompoundBody(input, new DataOutputStream(entry));
        byte[] fields = entry.toByteArray();
        String id = stringValue(fields, "id").orElse(null);
        int level = shortValue(fields, "lvl").orElse(intValue(fields, "lvl").orElse(0));
        if (id != null) found.add(new Enchantment(id, level));
      }
      return found;
    }).orElse(List.of());
  }

  public static Optional<Integer> shortValue(byte[] body, String key) {
    return find(body, key, 2, input -> (int) input.readShort());
  }

  // ---------------------------------------------------------------- internals

  private interface Reader<T> { T read(DataInput input) throws IOException; }

  /** Scans the top level of a compound body for {@code key} with the expected tag type. */
  private static <T> Optional<T> find(byte[] body, String key, int expectedType, Reader<T> reader) {
    if (body.length == 0) return Optional.empty();
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
      while (true) {
        int type = input.readUnsignedByte();
        if (type == 0) return Optional.empty();
        String name = input.readUTF();
        if (name.equals(key)) {
          if (type != expectedType) return Optional.empty();
          return Optional.ofNullable(reader.read(input));
        }
        skipPayload(input, type);
      }
    } catch (IOException exception) {
      return Optional.empty();                  // malformed NBT is simply "no value"
    }
  }

  private static void skipName(DataInput input) throws IOException {
    int length = input.readUnsignedShort();
    input.skipBytes(length);
  }

  /** Copies compound entries up to and including the terminating TAG_End. */
  static void copyCompoundBody(DataInput input, DataOutput output) throws IOException {
    while (true) {
      int type = input.readUnsignedByte();
      output.writeByte(type);
      if (type == 0) return;
      String name = input.readUTF();
      output.writeUTF(name);
      copyPayload(input, output, type);
    }
  }

  private static void skipPayload(DataInput input, int type) throws IOException {
    copyPayload(input, null, type);
  }

  private static void copyPayload(DataInput input, DataOutput output, int type) throws IOException {
    switch (type) {
      case 1 -> copyBytes(input, output, 1);
      case 2 -> copyBytes(input, output, 2);
      case 3, 5 -> copyBytes(input, output, 4);
      case 4, 6 -> copyBytes(input, output, 8);
      case 7 -> {
        int length = readInt(input, output);
        if (length < 0 || length > MAX_BODY) throw new IOException("nbt byte array " + length);
        copyBytes(input, output, length);
      }
      case 8 -> {
        int length = input.readUnsignedShort();
        if (output != null) output.writeShort(length);
        copyBytes(input, output, length);
      }
      case 9 -> {
        int element = input.readUnsignedByte();
        if (output != null) output.writeByte(element);
        int length = readInt(input, output);
        if (length < 0 || length > 65536) throw new IOException("nbt list " + length);
        for (int index = 0; index < length; index++) copyPayload(input, output, element);
      }
      case 10 -> {
        while (true) {
          int child = input.readUnsignedByte();
          if (output != null) output.writeByte(child);
          if (child == 0) return;
          int nameLength = input.readUnsignedShort();
          if (output != null) output.writeShort(nameLength);
          copyBytes(input, output, nameLength);
          copyPayload(input, output, child);
        }
      }
      case 11 -> {
        int length = readInt(input, output);
        if (length < 0 || length > 262_144) throw new IOException("nbt int array " + length);
        copyBytes(input, output, length * 4);
      }
      case 12 -> {
        int length = readInt(input, output);
        if (length < 0 || length > 131_072) throw new IOException("nbt long array " + length);
        copyBytes(input, output, length * 8);
      }
      default -> throw new IOException("unknown nbt tag type " + type);
    }
  }

  private static int readInt(DataInput input, DataOutput output) throws IOException {
    int value = input.readInt();
    if (output != null) output.writeInt(value);
    return value;
  }

  private static void copyBytes(DataInput input, DataOutput output, int length) throws IOException {
    byte[] data = new byte[length];
    input.readFully(data);
    if (output != null) output.write(data);
  }
}
