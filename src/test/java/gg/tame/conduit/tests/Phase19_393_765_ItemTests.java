package gg.tame.conduit.tests;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.ProtocolTranslator;
import gg.tame.conduit.protocol.Translators;
import gg.tame.conduit.protocol.chunk.BlockStateMaps;
import gg.tame.conduit.protocol.entity.AttributeCodec;
import gg.tame.conduit.protocol.entity.MetadataCodec;
import gg.tame.conduit.protocol.inventory.ContainerCodec;
import gg.tame.conduit.protocol.item.ItemCodec;
import gg.tame.conduit.protocol.item.ItemNbt;
import gg.tame.conduit.protocol.item.ItemRegistries;
import gg.tame.conduit.protocol.item.SemanticItem;
import gg.tame.conduit.protocol.text.ComponentCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.List;

/**
 * Phase 19 — 393↔765 items, inventory, containers, equipment, metadata, attributes.
 *
 * <p>These are unit tests over the codecs. They are necessary but not sufficient:
 * the real standard for this pair is a real client against a real
 * different-version server, recorded in RESULTS-393-765-CROSS.md.
 */
public final class Phase19_393_765_ItemTests {
  private Phase19_393_765_ItemTests() {}

  public static void run() throws Exception {
    itemIdentityIsByName();
    itemSlotRoundTrip();
    itemNbtSurvivesBothDirections();
    unknownItemFailsClosed();
    containerContentBothDirections();
    containerSlotCarriesStateId();
    clickWindowBothDirections();
    openWindowMapsScreens();
    equipmentFansOutToLegacy();
    equipmentMergesToModern();
    metadataIndexShift();
    metadataTypeShift();
    metadataThroughTranslator();
    attributeKeysRenamed();
    blockStatePropertiesSurvive();
    componentJsonNbtRoundTrip();
    transactionAckIsSynthesised();
    System.out.println("Phase19_393_765_ItemTests passed.");
  }

  // ------------------------------------------------------------------- items

  private static void itemIdentityIsByName() {
    // The same identifier has different numbers in the two registries, which is
    // exactly why translation cannot be numeric.
    int sword393 = ItemRegistries.id(393, "minecraft:diamond_sword").getAsInt();
    int sword765 = ItemRegistries.id(765, "minecraft:diamond_sword").getAsInt();
    require(sword393 != sword765, "diamond sword should have different ids in 393 and 765");
    require(ItemRegistries.translate(393, 765, sword393).getAsInt() == sword765,
        "393 diamond sword must translate to the 765 diamond sword");
    require(ItemRegistries.translate(765, 393, sword765).getAsInt() == sword393,
        "765 diamond sword must translate back");
    // A Mojang rename must be followed, not treated as a missing item.
    require("minecraft:red_dye".equals(ItemRegistries.name(765,
            ItemRegistries.translate(393, 765, ItemRegistries.id(393, "minecraft:rose_red").getAsInt()).getAsInt()).get()),
        "1.13 rose_red must become 1.20.4 red_dye");
  }

  private static void itemSlotRoundTrip() throws Exception {
    SemanticItem stack = SemanticItem.of("minecraft:diamond_sword", 1);
    byte[] legacy = encodeSlot(393, stack);
    SemanticItem viaLegacy = decodeSlot(393, legacy);
    require(viaLegacy.equals(stack), "393 slot round trip");

    byte[] modern = encodeSlot(765, stack);
    require(decodeSlot(765, modern).equals(stack), "765 slot round trip");

    // Cross-era: the wire bytes differ, the semantic item does not.
    require(!java.util.Arrays.equals(legacy, modern), "the two slot layouts must differ on the wire");
    require(decodeSlot(765, translateSlot(393, 765, legacy)).equals(stack), "393 -> 765 slot");
    require(decodeSlot(393, translateSlot(765, 393, modern)).equals(stack), "765 -> 393 slot");
  }

  private static void itemNbtSurvivesBothDirections() throws Exception {
    byte[] tag = tagWithDamageAndName(42, "{\"text\":\"Excalibur\"}");
    SemanticItem stack = new SemanticItem("minecraft:diamond_sword", 1, tag);
    require(stack.damage().orElse(-1) == 42, "damage read from tag");
    require(stack.displayName().orElse("").contains("Excalibur"), "display name read from tag");

    byte[] crossed = translateSlot(393, 765, encodeSlot(393, stack));
    SemanticItem modern = decodeSlot(765, crossed);
    require(modern.damage().orElse(-1) == 42, "durability survives 393 -> 765");
    require(modern.displayName().orElse("").contains("Excalibur"), "display name survives 393 -> 765");

    SemanticItem back = decodeSlot(393, translateSlot(765, 393, crossed));
    require(back.equals(stack), "item NBT round-trips 393 -> 765 -> 393 byte for byte");
  }

  private static void unknownItemFailsClosed() throws Exception {
    // Netherite has no 1.13 identity. It must arrive as an empty slot, never as
    // whatever item happens to hold that number in the 1.13 registry.
    SemanticItem netherite = SemanticItem.of("minecraft:netherite_ingot", 3);
    require(ItemRegistries.translate(765, 393,
        ItemRegistries.id(765, "minecraft:netherite_ingot").getAsInt()).isEmpty(),
        "netherite must have no 393 mapping");
    byte[] legacy = translateSlot(765, 393, encodeSlot(765, netherite));
    require(decodeSlot(393, legacy).isEmpty(), "unmapped item must become an empty slot");
  }

  // -------------------------------------------------------------- containers

  private static void containerContentBothDirections() throws Exception {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(body);
    out.writeByte(3);                                  // windowId
    out.writeShort(2);                                 // slot count
    ItemCodec.write(393, out, SemanticItem.of("minecraft:stone", 64));
    ItemCodec.write(393, out, SemanticItem.EMPTY);

    byte[] modern = ContainerCodec.containerContent(393, 765, body.toByteArray(), 7);
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(modern))) {
      require(in.readUnsignedByte() == 3, "window id preserved");
      require(MinecraftInput.varInt(in) == 7, "state id synthesised for the modern client");
      require(MinecraftInput.varInt(in) == 2, "slot count preserved");
      require(ItemCodec.read(765, in).equals(SemanticItem.of("minecraft:stone", 64)), "slot 0");
      require(ItemCodec.read(765, in).isEmpty(), "slot 1 empty");
      require(ItemCodec.read(765, in).isEmpty(), "carried item synthesised empty");
    }

    // And back: the 1.13 form must not carry a state id or a carried item.
    byte[] legacy = ContainerCodec.containerContent(765, 393, modern, 0);
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(legacy))) {
      require(in.readUnsignedByte() == 3, "window id preserved back");
      require(in.readShort() == 2, "slot count is a short on 393");
      require(ItemCodec.read(393, in).equals(SemanticItem.of("minecraft:stone", 64)), "slot 0 back");
      require(ItemCodec.read(393, in).isEmpty(), "slot 1 back");
      require(in.available() == 0, "393 content ends after the slots");
    }
  }

  private static void containerSlotCarriesStateId() throws Exception {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(body);
    out.writeByte(0);
    out.writeShort(36);                                // hotbar slot 0 in the player inventory
    ItemCodec.write(393, out, SemanticItem.of("minecraft:oak_log", 12));

    byte[] modern = ContainerCodec.containerSlot(393, 765, body.toByteArray(), 99);
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(modern))) {
      require(in.readByte() == 0, "window id");
      require(MinecraftInput.varInt(in) == 99, "state id");
      require(in.readShort() == 36, "slot number is unchanged: the layouts match");
      require(ItemCodec.read(765, in).equals(SemanticItem.of("minecraft:oak_log", 12)), "item");
    }
  }

  private static void clickWindowBothDirections() throws Exception {
    // 1.13 click: windowId, slot, button, action, mode, clicked item
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(body);
    out.writeByte(1);
    out.writeShort(5);
    out.writeByte(0);
    out.writeShort(77);                                // action number
    MinecraftOutput.varInt(out, 0);                    // mode
    ItemCodec.write(393, out, SemanticItem.of("minecraft:stone", 1));

    var click = ContainerCodec.readClick(393, body.toByteArray());
    require(click.windowId() == 1 && click.slot() == 5 && click.mode() == 0, "click fields");
    require(click.carried().identifier().equals("minecraft:stone"), "clicked item");

    byte[] modern = ContainerCodec.writeClick(765, click, 12, 0);
    var back = ContainerCodec.readClick(765, modern);
    require(back.windowId() == 1 && back.slot() == 5 && back.button() == 0 && back.mode() == 0,
        "click survives 393 -> 765");
    require(back.carried().identifier().equals("minecraft:stone"), "carried item survives");

    // And a modern click rebuilt for 1.13 must carry an action number.
    byte[] legacy = ContainerCodec.writeClick(393, back, 0, 5);
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(legacy))) {
      in.readUnsignedByte(); in.readShort(); in.readByte();
      require(in.readShort() == 5, "1.13 click must carry the synthesised action number");
    }
  }

  private static void openWindowMapsScreens() throws Exception {
    require(ContainerCodec.menuId("minecraft:chest", 27).orElse(-1) == 2,
        "a 27-slot chest is generic_9x3");
    require(ContainerCodec.menuId("minecraft:chest", 54).orElse(-1) == 5,
        "a double chest is generic_9x6");
    require(ContainerCodec.menuId("minecraft:furnace", 3).orElse(-1) == 14, "furnace");
    require(ContainerCodec.menuId("minecraft:horse", 0).isEmpty(), "no 1.20.4 menu for a horse screen");
    require(ContainerCodec.windowType393(2).get()[0].equals("minecraft:chest"), "generic_9x3 back to chest");
    require(ContainerCodec.windowType393(18).isEmpty(), "loom has no 1.13 screen");

    ByteArrayOutputStream body = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(body);
    out.writeByte(4);
    MinecraftOutput.string(out, "minecraft:chest");
    MinecraftOutput.string(out, "{\"text\":\"Chest\"}");
    out.writeByte(27);
    byte[] modern = ContainerCodec.openWindow(393, 765, body.toByteArray());
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(modern))) {
      require(MinecraftInput.varInt(in) == 4, "window id");
      require(MinecraftInput.varInt(in) == 2, "menu registry id");
      require(ComponentCodec.nbtToJson(in).contains("Chest"), "title converted to NBT component");
    }
  }

  // --------------------------------------------------------------- equipment

  private static void equipmentFansOutToLegacy() throws Exception {
    List<ContainerCodec.Equipment> changes = List.of(
        new ContainerCodec.Equipment(11, 0, SemanticItem.of("minecraft:diamond_sword", 1)),
        new ContainerCodec.Equipment(11, 5, SemanticItem.of("minecraft:iron_helmet", 1)));
    byte[] merged = ContainerCodec.writeEquipment765(changes);
    var read = ContainerCodec.readEquipment(765, merged);
    require(read.size() == 2, "modern packet carries both slots");
    require(read.get(0).slot() == 0 && read.get(1).slot() == 5, "slots preserved");
    require(read.get(1).item().identifier().equals("minecraft:iron_helmet"), "helmet preserved");

    byte[] one = ContainerCodec.writeEquipment393(read.get(1));
    var legacy = ContainerCodec.readEquipment(393, one);
    require(legacy.size() == 1 && legacy.get(0).slot() == 5, "1.13 carries exactly one slot");
  }

  private static void equipmentMergesToModern() throws Exception {
    byte[] one = ContainerCodec.writeEquipment393(
        new ContainerCodec.Equipment(7, 2, SemanticItem.of("minecraft:leather_boots", 1)));
    var read = ContainerCodec.readEquipment(393, one);
    byte[] modern = ContainerCodec.writeEquipment765(read);
    var back = ContainerCodec.readEquipment(765, modern);
    require(back.size() == 1 && back.get(0).slot() == 2, "single slot survives 393 -> 765");
    require(back.get(0).item().identifier().equals("minecraft:leather_boots"), "boots survive");
  }

  // ---------------------------------------------------------------- metadata

  private static void metadataIndexShift() {
    // Entity base is untouched; LivingEntity shifts by 2; everything below Mob by 4.
    require(MetadataCodec.mapIndexToModern(0, true) == 0, "flags stay at 0");
    require(MetadataCodec.mapIndexToModern(2, true) == 2, "custom name stays at 2");
    require(MetadataCodec.mapIndexToModern(7, true) == 9, "living health 7 -> 9");
    require(MetadataCodec.mapIndexToModern(12, true) == 16, "zombie isBaby 12 -> 16");
    require(MetadataCodec.mapIndexToModern(6, false) == 8, "item entity's stack 6 -> 8");
    require(MetadataCodec.mapIndexToLegacy(9, true) == 7, "living health 9 -> 7");
    require(MetadataCodec.mapIndexToLegacy(16, true) == 12, "zombie isBaby 16 -> 12");
    require(MetadataCodec.mapIndexToLegacy(8, false) == 6, "item entity's stack 8 -> 6");
    require(MetadataCodec.mapIndexToLegacy(6, true) < 0, "pose has no 1.13 field");
    require(MetadataCodec.mapIndexToLegacy(13, true) < 0, "bee stingers have no 1.13 field");
  }

  private static void metadataTypeShift() {
    // The hazard this exists to prevent: 765 Float (3) is 393 String (3).
    require(MetadataCodec.mapType393To765(2) == 3, "393 Float 2 -> 765 Float 3");
    require(MetadataCodec.mapType765To393(3) == 2, "765 Float 3 -> 393 Float 2");
    require(MetadataCodec.mapType393To765(6) == 7, "393 Slot 6 -> 765 Slot 7");
    require(MetadataCodec.mapType765To393(2) < 0, "VarLong has no 393 type");
    require(MetadataCodec.mapType765To393(20) < 0, "Pose has no 393 type");
  }

  private static void metadataThroughTranslator() throws Exception {
    ProtocolDefinition v393 = ProtocolDefinition.forVersion(393);
    ProtocolDefinition v765 = ProtocolDefinition.forVersion(765);

    // A zombie: base flags (Byte, index 0), living health (Float, index 7),
    // and isBaby (Boolean, index 12).
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(body);
    out.writeByte(0); MinecraftOutput.varInt(out, 0); out.writeByte(0x01);
    out.writeByte(7); MinecraftOutput.varInt(out, 2); out.writeFloat(20f);
    out.writeByte(12); MinecraftOutput.varInt(out, 7); out.writeBoolean(true);
    out.writeByte(0xff);

    byte[] modern = MetadataCodec.translate(v393, v765, body.toByteArray(), true);
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(modern))) {
      require(in.readUnsignedByte() == 0 && MinecraftInput.varInt(in) == 0 && in.readByte() == 1, "flags");
      require(in.readUnsignedByte() == 9, "health index shifted to 9");
      require(MinecraftInput.varInt(in) == 3, "health type shifted to Float 3");
      require(in.readFloat() == 20f, "health value");
      require(in.readUnsignedByte() == 16, "isBaby index shifted to 16");
      require(MinecraftInput.varInt(in) == 8, "boolean type shifted to 8");
      require(in.readBoolean(), "isBaby value");
      require(in.readUnsignedByte() == 0xff, "terminator");
    }

    byte[] back = MetadataCodec.translate(v765, v393, modern, true);
    require(java.util.Arrays.equals(back, body.toByteArray()), "metadata round-trips exactly");

    // A modern-only field must be consumed, not left to desynchronise the rest.
    ByteArrayOutputStream withPose = new ByteArrayOutputStream();
    DataOutputStream poseOut = new DataOutputStream(withPose);
    poseOut.writeByte(6); MinecraftOutput.varInt(poseOut, 20); MinecraftOutput.varInt(poseOut, 5);
    poseOut.writeByte(9); MinecraftOutput.varInt(poseOut, 3); poseOut.writeFloat(15f);
    poseOut.writeByte(0xff);
    byte[] legacy = MetadataCodec.translate(v765, v393, withPose.toByteArray(), true);
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(legacy))) {
      require(in.readUnsignedByte() == 7, "pose dropped, health still lands on index 7");
      require(MinecraftInput.varInt(in) == 2, "health is 393 Float type 2");
      require(in.readFloat() == 15f, "health value survives a dropped field in front of it");
    }
  }

  // -------------------------------------------------------------- attributes

  private static void attributeKeysRenamed() throws Exception {
    require("minecraft:generic.max_health".equals(AttributeCodec.mapKey(765, "generic.maxHealth")),
        "1.13 maxHealth renames to the 1.16+ identifier");
    require("generic.movementSpeed".equals(AttributeCodec.mapKey(393, "minecraft:generic.movement_speed")),
        "and back again");

    ByteArrayOutputStream body = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(body);
    MinecraftOutput.varInt(out, 42);
    out.writeInt(2);                                   // 1.13 uses a fixed Int count
    MinecraftOutput.string(out, "generic.maxHealth");
    out.writeDouble(20.0);
    MinecraftOutput.varInt(out, 0);
    MinecraftOutput.string(out, "generic.movementSpeed");
    out.writeDouble(0.1);
    MinecraftOutput.varInt(out, 0);

    byte[] modern = AttributeCodec.translate(393, 765, body.toByteArray());
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(modern))) {
      require(MinecraftInput.varInt(in) == 42, "entity id");
      require(MinecraftInput.varInt(in) == 2, "count becomes a VarInt");
      require(MinecraftInput.string(in, 256).equals("minecraft:generic.max_health"), "key renamed");
      require(in.readDouble() == 20.0, "value");
      require(MinecraftInput.varInt(in) == 0, "no modifiers");
      require(MinecraftInput.string(in, 256).equals("minecraft:generic.movement_speed"), "second key");
    }
    require(java.util.Arrays.equals(AttributeCodec.translate(765, 393, modern), body.toByteArray()),
        "attributes round-trip exactly");
  }

  // ------------------------------------------------------------ block states

  private static void blockStatePropertiesSurvive() {
    // A stair's facing/half/shape must survive, not collapse to the default state.
    // These are the ids the official 1.13 and 1.20.4 servers report for the same
    // oak stair: north / top / straight / not waterlogged.
    int oakStairNorthTop393 = 1649;
    int mapped = BlockStateMaps.to765(oakStairNorthTop393);
    require(BlockStateMaps.to393(mapped) == oakStairNorthTop393,
        "a specific stair state must round-trip, properties and all");
    require(BlockStateMaps.to765(0) == 0, "air stays air");
    // Air, not stone, for anything genuinely absent.
    require(BlockStateMaps.to393(Integer.MAX_VALUE - 1) == 0, "out of range resolves to air");
  }

  private static void componentJsonNbtRoundTrip() throws Exception {
    String json = "{\"text\":\"hello\",\"color\":\"red\",\"extra\":[{\"text\":\" world\"}]}";
    byte[] nbt = ComponentCodec.jsonToNbtBytes(json);
    String back = ComponentCodec.nbtBytesToJson(nbt);
    require(back.contains("hello") && back.contains("red") && back.contains(" world"),
        "structure survives JSON -> NBT -> JSON, not just the plain text");
  }

  // ------------------------------------------------- the transaction handshake

  private static void transactionAckIsSynthesised() throws Exception {
    ProtocolTranslator translator = Translators.forPair(393, 765);
    ProtocolDefinition v393 = ProtocolDefinition.forVersion(393);

    ByteArrayOutputStream body = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(body);
    out.writeByte(1);
    out.writeShort(9);
    out.writeByte(0);
    out.writeShort(1234);                              // action number the client chose
    MinecraftOutput.varInt(out, 0);
    ItemCodec.write(393, out, SemanticItem.EMPTY);

    byte[] click = PlayPackets.withId(
        v393.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CLICK_WINDOW),
        body.toByteArray());
    byte[] translated = translator.clientToBackend(ConnectionState.PLAY, click);
    require(translated != null, "the click itself reaches the 1.20.4 backend");

    List<byte[]> extras = translator.drainToClient();
    require(extras.size() == 1, "exactly one transaction confirmation is queued for the 1.13 client");
    require(PlayPackets.packetId(extras.get(0))
        == v393.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_CONFIRM_TRANSACTION),
        "and it is a Confirm Transaction");
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(extras.get(0))))) {
      require(in.readUnsignedByte() == 1, "same window");
      require(in.readShort() == 1234, "same action number the client sent");
      require(in.readBoolean(), "accepted");
    }
    require(translator.drainToClient().isEmpty(), "the queue is drained, not replayed");
  }

  // ------------------------------------------------------------------ helpers

  private static byte[] encodeSlot(int protocol, SemanticItem item) throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    ItemCodec.write(protocol, new DataOutputStream(buffer), item);
    return buffer.toByteArray();
  }

  private static SemanticItem decodeSlot(int protocol, byte[] bytes) throws Exception {
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
      return ItemCodec.read(protocol, in);
    }
  }

  private static byte[] translateSlot(int from, int to, byte[] bytes) throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
      ItemCodec.translate(from, to, in, new DataOutputStream(buffer));
    }
    return buffer.toByteArray();
  }

  /** An item NBT compound body holding {@code Damage} and {@code display.Name}. */
  private static byte[] tagWithDamageAndName(int damage, String nameJson) throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    out.writeByte(3); out.writeUTF("Damage"); out.writeInt(damage);
    out.writeByte(10); out.writeUTF("display");
    out.writeByte(8); out.writeUTF("Name"); out.writeUTF(nameJson);
    out.writeByte(0);
    out.writeByte(0);
    return buffer.toByteArray();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
