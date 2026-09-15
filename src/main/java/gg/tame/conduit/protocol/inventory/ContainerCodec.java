package gg.tame.conduit.protocol.inventory;

import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.item.ItemCodec;
import gg.tame.conduit.protocol.item.SemanticItem;
import gg.tame.conduit.protocol.text.ComponentCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Container and equipment packets between protocol 393 and 765.
 *
 * <p>Slot <em>numbering</em> is the same on both sides for the containers this
 * covers — a chest's 0..26 followed by the player's inventory, and the player's
 * own 0..45 with 5..8 armour, 45 off-hand — because the layouts themselves did
 * not change. What changed is the framing around them:
 *
 * <ul>
 *   <li>1.17 added a {@code stateId} to every container update, and the client
 *       echoes the last one it saw back in each click. A 1.13 server has no
 *       such counter, so Conduit keeps one per session.</li>
 *   <li>1.17 also moved the carried (cursor) item into the container packets,
 *       replacing 1.13's separate transaction dance.</li>
 *   <li>1.14 replaced the string window type with a registry id, and 1.20.3
 *       replaced the JSON title with an NBT text component.</li>
 *   <li>1.16 turned equipment from one (slot, item) pair into a
 *       top-bit-terminated array, so one modern packet can carry six slots that
 *       must become six 1.13 packets.</li>
 * </ul>
 */
public final class ContainerCodec {
  private ContainerCodec() {}

  // ------------------------------------------------- full container contents

  /**
   * Window Items / Set Container Content.
   *
   * <pre>
   *   393   windowId:u8  count:i16   slots[count]
   *   765   windowId:u8  stateId:VarInt  count:VarInt  slots[count]  carried:Slot
   * </pre>
   */
  public static byte[] containerContent(int fromProtocol, int toProtocol, byte[] body, int stateId)
      throws IOException {
    boolean fromModern = fromProtocol > 404;
    boolean toModern = toProtocol > 404;
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
      int windowId = in.readUnsignedByte();
      if (fromModern) MinecraftInput.varInt(in);              // stateId: regenerated below
      int count = fromModern ? MinecraftInput.varInt(in) : in.readShort();
      if (count < 0 || count > 4096) throw new IOException("container slot count " + count);

      List<SemanticItem> slots = new ArrayList<>(count);
      for (int index = 0; index < count; index++) slots.add(ItemCodec.read(fromProtocol, in));
      SemanticItem carried = fromModern ? ItemCodec.read(fromProtocol, in) : SemanticItem.EMPTY;

      ByteArrayOutputStream buffer = new ByteArrayOutputStream(body.length + 16);
      DataOutputStream out = new DataOutputStream(buffer);
      out.writeByte(windowId);
      if (toModern) {
        MinecraftOutput.varInt(out, stateId);
        MinecraftOutput.varInt(out, count);
      } else {
        out.writeShort(count);
      }
      for (SemanticItem item : slots) ItemCodec.write(toProtocol, out, item);
      // 1.13 has no carried-item field: the cursor stack lives on the client and
      // is reconciled through transactions instead.
      if (toModern) ItemCodec.write(toProtocol, out, carried);
      out.flush();
      return buffer.toByteArray();
    }
  }

  /**
   * Set Slot.
   *
   * <pre>
   *   393   windowId:i8  slot:i16  item
   *   765   windowId:i8  stateId:VarInt  slot:i16  item
   * </pre>
   */
  public static byte[] containerSlot(int fromProtocol, int toProtocol, byte[] body, int stateId)
      throws IOException {
    boolean fromModern = fromProtocol > 404;
    boolean toModern = toProtocol > 404;
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
      int windowId = in.readByte();
      if (fromModern) MinecraftInput.varInt(in);
      int slot = in.readShort();
      SemanticItem item = ItemCodec.read(fromProtocol, in);

      ByteArrayOutputStream buffer = new ByteArrayOutputStream(body.length + 8);
      DataOutputStream out = new DataOutputStream(buffer);
      out.writeByte(windowId);
      if (toModern) MinecraftOutput.varInt(out, stateId);
      out.writeShort(slot);
      ItemCodec.write(toProtocol, out, item);
      out.flush();
      return buffer.toByteArray();
    }
  }

  // ---------------------------------------------------------------- clicking

  /** The fields of a container click, in the form both eras can be rebuilt from. */
  public record Click(int windowId, int slot, int button, int mode, SemanticItem carried) {}

  /**
   * Click Container.
   *
   * <pre>
   *   393   windowId:u8  slot:i16  button:i8  action:i16  mode:VarInt  clicked:Slot
   *   765   windowId:u8  stateId:VarInt  slot:i16  button:i8  mode:VarInt
   *         changed:VarInt{ slot:i16, item }[]  carried:Slot
   * </pre>
   *
   * <p>1.13's {@code action} number is the id the server echoes in its
   * transaction confirmation; 1.17 removed that handshake in favour of the
   * state id. Neither number can be carried across, so each direction
   * synthesises the one its target needs.
   */
  public static Click readClick(int fromProtocol, byte[] body) throws IOException {
    boolean fromModern = fromProtocol > 404;
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
      int windowId = in.readUnsignedByte();
      if (fromModern) MinecraftInput.varInt(in);              // stateId
      int slot = in.readShort();
      int button = in.readByte();
      if (!fromModern) in.readShort();                        // action number
      int mode = MinecraftInput.varInt(in);
      if (fromModern) {
        int changed = MinecraftInput.varInt(in);
        if (changed < 0 || changed > 4096) throw new IOException("changed slots " + changed);
        for (int index = 0; index < changed; index++) {
          in.readShort();
          ItemCodec.read(fromProtocol, in);
        }
      }
      SemanticItem carried = ItemCodec.read(fromProtocol, in);
      return new Click(windowId, slot, button, mode, carried);
    }
  }

  public static byte[] writeClick(int toProtocol, Click click, int stateId, int actionNumber)
      throws IOException {
    boolean toModern = toProtocol > 404;
    ByteArrayOutputStream buffer = new ByteArrayOutputStream(48);
    DataOutputStream out = new DataOutputStream(buffer);
    out.writeByte(click.windowId());
    if (toModern) MinecraftOutput.varInt(out, stateId);
    out.writeShort(click.slot());
    out.writeByte(click.button());
    if (!toModern) out.writeShort(actionNumber);
    MinecraftOutput.varInt(out, click.mode());
    if (toModern) {
      // The changed-slot array is the client telling the server what it already
      // applied optimistically. A 1.13 client does no such prediction, so an
      // empty array is the truthful value, not a shortcut.
      MinecraftOutput.varInt(out, 0);
    }
    ItemCodec.write(toProtocol, out, click.carried());
    out.flush();
    return buffer.toByteArray();
  }

  /** A 1.13 clientbound Confirm Transaction body: windowId, action, accepted. */
  public static byte[] confirmTransaction(int windowId, int actionNumber, boolean accepted)
      throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream(4);
    DataOutputStream out = new DataOutputStream(buffer);
    out.writeByte(windowId);
    out.writeShort(actionNumber);
    out.writeBoolean(accepted);
    out.flush();
    return buffer.toByteArray();
  }

  // ------------------------------------------------------------ opening a window

  /**
   * 1.13 window type identifier -> 1.20.4 {@code minecraft:menu} registry id.
   * Chests are chosen by row count, which is why the slot count is needed.
   */
  public static Optional<Integer> menuId(String type393, int slots) {
    return switch (type393) {
      case "minecraft:chest", "minecraft:container", "minecraft:shulker_box" -> {
        if (type393.equals("minecraft:shulker_box")) yield Optional.of(20);
        int rows = Math.max(1, Math.min(6, slots / 9));
        yield Optional.of(rows - 1);                 // generic_9x1 .. generic_9x6 are 0..5
      }
      case "minecraft:dispenser", "minecraft:dropper" -> Optional.of(6);
      case "minecraft:anvil" -> Optional.of(8);
      case "minecraft:beacon" -> Optional.of(9);
      case "minecraft:brewing_stand" -> Optional.of(11);
      case "minecraft:crafting_table" -> Optional.of(12);
      case "minecraft:enchanting_table" -> Optional.of(13);
      case "minecraft:furnace" -> Optional.of(14);
      case "minecraft:hopper" -> Optional.of(16);
      case "minecraft:villager" -> Optional.of(19);
      default -> Optional.empty();                   // horse screens and unknowns fail closed
    };
  }

  /** 1.20.4 menu id -> (1.13 window type, slot count). */
  public static Optional<String[]> windowType393(int menuId) {
    return switch (menuId) {
      case 0 -> Optional.of(new String[] {"minecraft:chest", "9"});
      case 1 -> Optional.of(new String[] {"minecraft:chest", "18"});
      case 2 -> Optional.of(new String[] {"minecraft:chest", "27"});
      case 3 -> Optional.of(new String[] {"minecraft:chest", "36"});
      case 4 -> Optional.of(new String[] {"minecraft:chest", "45"});
      case 5 -> Optional.of(new String[] {"minecraft:chest", "54"});
      case 6, 7 -> Optional.of(new String[] {"minecraft:dispenser", "9"});
      case 8 -> Optional.of(new String[] {"minecraft:anvil", "3"});
      case 9 -> Optional.of(new String[] {"minecraft:beacon", "1"});
      // 1.13 has no blast furnace or smoker screen; the plain furnace screen has
      // the same three slots and the same semantics, so it is the honest stand-in.
      case 10, 14, 22 -> Optional.of(new String[] {"minecraft:furnace", "3"});
      case 11 -> Optional.of(new String[] {"minecraft:brewing_stand", "5"});
      case 12 -> Optional.of(new String[] {"minecraft:crafting_table", "10"});
      case 13 -> Optional.of(new String[] {"minecraft:enchanting_table", "2"});
      case 16 -> Optional.of(new String[] {"minecraft:hopper", "5"});
      case 19 -> Optional.of(new String[] {"minecraft:villager", "3"});
      case 20 -> Optional.of(new String[] {"minecraft:shulker_box", "27"});
      default -> Optional.empty();                   // grindstone, loom, smithing: no 1.13 screen
    };
  }

  /**
   * Open Window / Open Screen.
   *
   * <pre>
   *   393   windowId:u8  type:String  title:Chat(JSON)  slots:u8  [entityId:i32]
   *   765   windowId:VarInt  type:VarInt(menu registry)  title:Chat(NBT)
   * </pre>
   *
   * @return the translated body, or null when the target has no such screen
   */
  public static byte[] openWindow(int fromProtocol, int toProtocol, byte[] body) throws IOException {
    boolean fromModern = fromProtocol > 404;
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream(body.length + 32);
      DataOutputStream out = new DataOutputStream(buffer);
      if (fromModern) {
        int windowId = MinecraftInput.varInt(in);
        int menu = MinecraftInput.varInt(in);
        String title = ComponentCodec.nbtToJson(in);
        var legacy = windowType393(menu);
        if (legacy.isEmpty()) return null;
        out.writeByte(windowId);
        MinecraftOutput.string(out, legacy.get()[0]);
        MinecraftOutput.string(out, title);
        out.writeByte(Integer.parseInt(legacy.get()[1]));
      } else {
        int windowId = in.readUnsignedByte();
        String type = MinecraftInput.string(in, 32767);
        String title = MinecraftInput.string(in, 262_144);
        int slots = in.readUnsignedByte();
        var menu = menuId(type, slots);
        if (menu.isEmpty()) return null;
        MinecraftOutput.varInt(out, windowId);
        MinecraftOutput.varInt(out, menu.get());
        ComponentCodec.jsonToNbt(out, title);
      }
      out.flush();
      return buffer.toByteArray();
    }
  }

  // --------------------------------------------------------------- equipment

  /** One equipment change: slot 0 mainhand, 1 offhand, 2..5 boots..helmet on both eras. */
  public record Equipment(int entityId, int slot, SemanticItem item) {}

  /**
   * Reads an Entity Equipment body. 1.13 carries exactly one slot; 1.16 and later
   * carry a top-bit-terminated array, so a modern packet can yield up to six.
   */
  public static List<Equipment> readEquipment(int fromProtocol, byte[] body) throws IOException {
    boolean fromModern = fromProtocol > 404;
    List<Equipment> changes = new ArrayList<>(fromModern ? 6 : 1);
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
      int entityId = MinecraftInput.varInt(in);
      if (!fromModern) {
        int slot = MinecraftInput.varInt(in);
        changes.add(new Equipment(entityId, slot, ItemCodec.read(fromProtocol, in)));
        return changes;
      }
      while (true) {
        int raw = in.readUnsignedByte();
        changes.add(new Equipment(entityId, raw & 0x7f, ItemCodec.read(fromProtocol, in)));
        if ((raw & 0x80) == 0) return changes;
        if (changes.size() > 8) throw new IOException("equipment array too long");
      }
    }
  }

  /** Writes one 1.13 equipment packet body. */
  public static byte[] writeEquipment393(Equipment change) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream(32);
    DataOutputStream out = new DataOutputStream(buffer);
    MinecraftOutput.varInt(out, change.entityId());
    MinecraftOutput.varInt(out, change.slot());
    ItemCodec.write(393, out, change.item());
    out.flush();
    return buffer.toByteArray();
  }

  /** Writes the whole set as one 1.20.4 equipment packet body. */
  public static byte[] writeEquipment765(List<Equipment> changes) throws IOException {
    if (changes.isEmpty()) return null;
    ByteArrayOutputStream buffer = new ByteArrayOutputStream(64);
    DataOutputStream out = new DataOutputStream(buffer);
    MinecraftOutput.varInt(out, changes.get(0).entityId());
    for (int index = 0; index < changes.size(); index++) {
      boolean last = index == changes.size() - 1;
      Equipment change = changes.get(index);
      out.writeByte(last ? change.slot() : (change.slot() | 0x80));
      ItemCodec.write(765, out, change.item());
    }
    out.flush();
    return buffer.toByteArray();
  }

  // ------------------------------------------------------- creative mode slot

  /**
   * Set Creative Mode Slot: {@code slot:i16, item}. The framing is identical on
   * both releases; only the item payload needs translating.
   */
  public static byte[] creativeSlot(int fromProtocol, int toProtocol, byte[] body) throws IOException {
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
      short slot = in.readShort();
      SemanticItem item = ItemCodec.read(fromProtocol, in);
      ByteArrayOutputStream buffer = new ByteArrayOutputStream(body.length + 8);
      DataOutputStream out = new DataOutputStream(buffer);
      out.writeShort(slot);
      ItemCodec.write(toProtocol, out, item);
      out.flush();
      return buffer.toByteArray();
    }
  }
}
