// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol.item;

import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The wire form of one inventory slot, for protocol 393 and 765.
 *
 * <p>The two layouts are genuinely different, not merely reordered:
 *
 * <pre>
 *   393        itemId:i16 (-1 empty)  [ count:i8  nbt(NAMED root) ]
 *   404..763   present:bool           [ itemId:VarInt  count:i8  nbt(NAMED root) ]
 *   764..765   present:bool           [ itemId:VarInt  count:i8  nbt(NAMELESS root) ]
 *   766+       count:VarInt           [ itemId:VarInt  components... ]
 * </pre>
 *
 * <p>Both boundaries here are easy to get wrong, and both were found by a real
 * client rejecting real bytes rather than by reasoning about release notes.
 *
 * <p>The {@code present} boolean with a VarInt id arrived in <b>1.13.2</b>
 * (protocol 404), not in 1.13: protocol 393 still writes a signed short id where
 * -1 means empty. Reading a 1.13 server's dropped-item metadata with the 1.13.2
 * layout eats one byte too many and then trips over the metadata terminator.
 *
 * <p>The count-first layout arrived in <b>1.20.5</b> (protocol 766) with item
 * data components — <b>not</b> in 1.20.4. Writing it to a 1.20.4 client makes it
 * read the count as the {@code present} flag and then hit the next field as an
 * NBT tag type, which it reports only as "Loading NBT data".
 *
 * <p>What 1.20.2 (protocol 764) did change is the NBT root: network NBT lost its
 * root name there, so 765 keeps the 1.13.2 field order but a nameless root.
 *
 * <p>Items with no counterpart in the target registry decode to
 * {@link SemanticItem#EMPTY}. That is the fail-closed choice this pair needs: a
 * 1.20.4 netherite ingot has no 1.13 identity, and showing the player an empty
 * slot is honest where showing them whatever item happens to hold that number in
 * 1.13 is not.
 */
public final class ItemCodec {
  private ItemCodec() {}

  /** Protocol 764 = 1.20.2, where network NBT lost its root name. */
  private static final int NAMELESS_NBT_FROM = 764;
  /** Protocol 766 = 1.20.5, where the slot became count-first with data components. */
  private static final int COUNT_FIRST_FROM = 766;
  /** Protocol 404 = 1.13.2, where the short id became a present flag plus a VarInt id. */
  private static final int PRESENT_FLAG_FROM = 404;

  private static boolean shortIdForm(int protocol) { return protocol < PRESENT_FLAG_FROM; }
  private static boolean countFirstForm(int protocol) { return protocol >= COUNT_FIRST_FROM; }
  private static boolean namedNbtRoot(int protocol) { return protocol < NAMELESS_NBT_FROM; }

  /** Reads one slot in the given protocol's layout. */
  public static SemanticItem read(int protocol, DataInput input) throws IOException {
    if (shortIdForm(protocol)) {
      int id = input.readShort();
      if (id < 0) return SemanticItem.EMPTY;
      int count = input.readByte();
      return stack(protocol, id, count, ItemNbt.readTag(input, true));
    }
    if (countFirstForm(protocol)) {
      int count = MinecraftInput.varInt(input);
      if (count <= 0) return SemanticItem.EMPTY;
      int id = MinecraftInput.varInt(input);
      return stack(protocol, id, count, ItemNbt.readTag(input, false));
    }
    if (!input.readBoolean()) return SemanticItem.EMPTY;
    int id = MinecraftInput.varInt(input);
    int count = input.readByte();
    return stack(protocol, id, count, ItemNbt.readTag(input, namedNbtRoot(protocol)));
  }

  /** Writes one slot in the given protocol's layout, or the empty slot. */
  public static void write(int protocol, DataOutput output, SemanticItem item) throws IOException {
    OptionalInt id = item.isEmpty() ? OptionalInt.empty() : ItemRegistries.id(protocol, item.identifier());
    if (item.isEmpty() || id.isEmpty()) {
      // Unknown on this side: fail closed rather than emit an arbitrary id.
      if (shortIdForm(protocol)) output.writeShort(-1);
      else if (countFirstForm(protocol)) MinecraftOutput.varInt(output, 0);
      else output.writeBoolean(false);
      return;
    }
    int count = Math.min(item.count(), 127);
    if (shortIdForm(protocol)) {
      output.writeShort(id.getAsInt());
      output.writeByte(count);
      ItemNbt.writeTag(output, item.tag(), true);
      return;
    }
    if (countFirstForm(protocol)) {
      MinecraftOutput.varInt(output, count);
      MinecraftOutput.varInt(output, id.getAsInt());
      ItemNbt.writeTag(output, item.tag(), false);
      return;
    }
    output.writeBoolean(true);
    MinecraftOutput.varInt(output, id.getAsInt());
    output.writeByte(count);
    ItemNbt.writeTag(output, item.tag(), namedNbtRoot(protocol));
  }

  /** Reads a slot in one protocol and writes it straight out in the other. */
  public static void translate(int fromProtocol, int toProtocol, DataInput input, DataOutput output)
      throws IOException {
    write(toProtocol, output, read(fromProtocol, input));
  }

  private static SemanticItem stack(int protocol, int id, int count, byte[] tag) {
    Optional<String> name = ItemRegistries.name(protocol, id);
    if (name.isEmpty() || count <= 0) return SemanticItem.EMPTY;
    return new SemanticItem(name.get(), count, tag);
  }
}
