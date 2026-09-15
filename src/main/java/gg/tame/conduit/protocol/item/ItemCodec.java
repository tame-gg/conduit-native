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
 *   393   present:bool  [ itemId:VarInt  count:i8   nbt(named root) ]
 *   765   count:VarInt  [ itemId:VarInt             nbt(nameless root) ]
 * </pre>
 *
 * <p>1.20.2 moved the "is there anything here" signal into the count itself and
 * dropped the root name from network NBT. So emptiness, count width and NBT
 * framing all have to be rebuilt; only the identifier and the compound contents
 * carry over, and the identifier only after a name-based registry translation.
 *
 * <p>Items with no counterpart in the target registry decode to
 * {@link SemanticItem#EMPTY}. That is the fail-closed choice this pair needs: a
 * 1.20.4 netherite ingot has no 1.13 identity, and showing the player an empty
 * slot is honest where showing them whatever item happens to hold that number in
 * 1.13 is not.
 */
public final class ItemCodec {
  private ItemCodec() {}

  /** Reads one slot in the given protocol's layout. */
  public static SemanticItem read(int protocol, DataInput input) throws IOException {
    boolean legacy = protocol <= 404;
    int count;
    if (legacy) {
      if (!input.readBoolean()) return SemanticItem.EMPTY;
      int id = MinecraftInput.varInt(input);
      count = input.readByte();
      byte[] tag = ItemNbt.readTag(input, true);
      return stack(protocol, id, count, tag);
    }
    count = MinecraftInput.varInt(input);
    if (count <= 0) return SemanticItem.EMPTY;
    int id = MinecraftInput.varInt(input);
    byte[] tag = ItemNbt.readTag(input, false);
    return stack(protocol, id, count, tag);
  }

  /** Writes one slot in the given protocol's layout, or the empty slot. */
  public static void write(int protocol, DataOutput output, SemanticItem item) throws IOException {
    boolean legacy = protocol <= 404;
    OptionalInt id = item.isEmpty() ? OptionalInt.empty() : ItemRegistries.id(protocol, item.identifier());
    if (item.isEmpty() || id.isEmpty()) {
      // Unknown on this side: fail closed rather than emit an arbitrary id.
      if (legacy) output.writeBoolean(false); else MinecraftOutput.varInt(output, 0);
      return;
    }
    if (legacy) {
      output.writeBoolean(true);
      MinecraftOutput.varInt(output, id.getAsInt());
      output.writeByte(Math.min(item.count(), 127));
      ItemNbt.writeTag(output, item.tag(), true);
      return;
    }
    MinecraftOutput.varInt(output, Math.min(item.count(), 127));
    MinecraftOutput.varInt(output, id.getAsInt());
    ItemNbt.writeTag(output, item.tag(), false);
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
