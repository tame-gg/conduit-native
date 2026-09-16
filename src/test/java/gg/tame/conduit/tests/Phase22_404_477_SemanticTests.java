package gg.tame.conduit.tests;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.chunk.BlockStateMaps;
import gg.tame.conduit.protocol.chunk.ChunkCodec393;
import gg.tame.conduit.protocol.chunk.ChunkCodec477;
import gg.tame.conduit.protocol.chunk.SemanticChunk;
import gg.tame.conduit.protocol.chunk.SemanticChunkSection;
import gg.tame.conduit.protocol.entity.EntityTypeMaps;
import gg.tame.conduit.protocol.entity.MetadataCodec;
import gg.tame.conduit.protocol.item.ItemCodec;
import gg.tame.conduit.protocol.item.ItemRegistries;
import gg.tame.conduit.protocol.item.SemanticItem;
import gg.tame.conduit.protocol.translate.Protocol404To477Translator;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 404 ↔ 477 semantic block-state, item and entity mapping.
 *
 * <p>These assertions are about <em>meaning</em>, not about bytes moving: the
 * numbers on the two sides are mostly different, and the point of every test
 * here is that the block the player sees and the item they hold are the same one
 * after the crossing.
 */
public final class Phase22_404_477_SemanticTests {
  private Phase22_404_477_SemanticTests() {}

  public static void run() throws Exception {
    blockStatesAreNotPassthrough();
    blockStateRoundTrip();
    itemsAreNotPassthrough();
    itemRoundTripWithNbt();
    itemsFailClosed();
    entityTypesResolveByName();
    metadataIndexShift();
    chunkTranslationRemapsPalette();
    chunkSectionCarriesBlockCount();
    spawnMobTranslatesTrailingMetadata();
    blockUpdateRemapsState();
    System.out.println("Phase22_404_477_SemanticTests passed.");
  }

  /**
   * The regression this whole layer exists to prevent. If these ever become
   * equal again, something has reintroduced numeric passthrough.
   */
  private static void blockStatesAreNotPassthrough() {
    int stone404 = 1;
    require(BlockStateMaps.translate(404, 477, stone404) == 1, "stone is 1 on both sides");

    // 1.14 expanded the note-block instruments, so everything above id 748 moves.
    int oakStairs404 = ItemRegistries.id(404, "minecraft:oak_stairs").getAsInt();
    require(oakStairs404 > 0, "oak stairs exist on 404");

    int identical = 0;
    for (int state = 0; state < 8599; state++) {
      if (BlockStateMaps.translate(404, 477, state) == state) identical++;
    }
    require(identical == 748,
        "exactly 748 of 8599 states keep their id; got " + identical);
    require(BlockStateMaps.translate(404, 477, 748) != 748,
        "the first divergent state must actually move");
  }

  private static void blockStateRoundTrip() {
    int failures = 0;
    for (int state = 0; state < 8599; state++) {
      int forward = BlockStateMaps.translate(404, 477, state);
      if (BlockStateMaps.translate(477, 404, forward) != state) failures++;
    }
    require(failures == 0, "all 8599 states round-trip 404→477→404; " + failures + " failed");

    // States 1.14 added have no 1.13.2 counterpart and must fail closed to air,
    // never to a solid block the player could stand on that is not there.
    require(BlockStateMaps.translate(477, 404, 11270) == 0, "1.14-only state → air");
    require(BlockStateMaps.translate(404, 477, Integer.MAX_VALUE) == 0, "out of range → air");
    require(BlockStateMaps.supports(404, 477) && BlockStateMaps.supports(477, 404),
        "pair is backed by a generated table");
    require(!BlockStateMaps.supports(404, 999), "unmodelled pair is not claimed");
  }

  private static void itemsAreNotPassthrough() {
    int identical = 0;
    int size = ItemRegistries.size(404);
    for (int id = 0; id < size; id++) {
      var mapped = ItemRegistries.translate(404, 477, id);
      if (mapped.isPresent() && mapped.getAsInt() == id) identical++;
    }
    require(identical == 108, "exactly 108 of 790 item ids survive; got " + identical);

    // The specific renames 1.14 performed.
    require(nameAfter(404, 477, "minecraft:rose_red").equals("minecraft:red_dye"), "rose_red → red_dye");
    require(nameAfter(404, 477, "minecraft:cactus_green").equals("minecraft:green_dye"), "cactus_green → green_dye");
    require(nameAfter(404, 477, "minecraft:dandelion_yellow").equals("minecraft:yellow_dye"), "dandelion_yellow → yellow_dye");
    require(nameAfter(404, 477, "minecraft:sign").equals("minecraft:oak_sign"), "sign → oak_sign");

    // And the 404 registry must be the 1.13.2 one, not 1.13's: 1.13.1 inserted
    // the dead corals at 443, so these two protocols disagree from there on.
    require(!ItemRegistries.name(393, 443).equals(ItemRegistries.name(404, 443)),
        "393 and 404 must not share one item table");
  }

  private static String nameAfter(int from, int to, String identifier) {
    int id = ItemRegistries.id(from, identifier).getAsInt();
    return ItemRegistries.name(to, ItemRegistries.translate(from, to, id).getAsInt()).get();
  }

  /** A real stack: custom name, lore, enchantments and damage must all survive. */
  private static void itemRoundTripWithNbt() throws Exception {
    byte[] tag = enchantedSwordTag();
    SemanticItem original = new SemanticItem("minecraft:diamond_sword", 1, tag);

    ByteArrayOutputStream as404 = new ByteArrayOutputStream();
    ItemCodec.write(404, new DataOutputStream(as404), original);
    SemanticItem read404 = ItemCodec.read(404,
        new DataInputStream(new ByteArrayInputStream(as404.toByteArray())));

    ByteArrayOutputStream as477 = new ByteArrayOutputStream();
    ItemCodec.write(477, new DataOutputStream(as477), read404);
    SemanticItem read477 = ItemCodec.read(477,
        new DataInputStream(new ByteArrayInputStream(as477.toByteArray())));

    require("minecraft:diamond_sword".equals(read477.identifier()), "identifier survives to 477");
    require(read477.count() == 1, "count survives");
    require(java.util.Arrays.equals(read477.tag(), tag), "NBT (name, lore, enchants, damage) survives");

    // The numeric ids genuinely differ; that is the point.
    int id404 = ItemRegistries.id(404, "minecraft:diamond_sword").getAsInt();
    int id477 = ItemRegistries.id(477, "minecraft:diamond_sword").getAsInt();
    require(id404 != id477, "diamond sword has a different id on each side");

    // Back down again.
    ByteArrayOutputStream back = new ByteArrayOutputStream();
    ItemCodec.write(404, new DataOutputStream(back), read477);
    SemanticItem returned = ItemCodec.read(404,
        new DataInputStream(new ByteArrayInputStream(back.toByteArray())));
    require("minecraft:diamond_sword".equals(returned.identifier()), "477→404 identifier survives");
    require(java.util.Arrays.equals(returned.tag(), tag), "477→404 NBT survives");
  }

  private static void itemsFailClosed() {
    // A 1.14 item with no 1.13.2 counterpart must not become a different item.
    int crossbow = ItemRegistries.id(477, "minecraft:crossbow").getAsInt();
    require(ItemRegistries.translate(477, 404, crossbow).isEmpty(),
        "crossbow has no 1.13.2 counterpart and must fail closed");
    int bamboo = ItemRegistries.id(477, "minecraft:bamboo").getAsInt();
    require(ItemRegistries.translate(477, 404, bamboo).isEmpty(), "bamboo fails closed");
    require(ItemRegistries.translate(404, 477, 99_999).isEmpty(), "out-of-range fails closed");
    require(ItemRegistries.name(477, 99_999).isEmpty(), "unknown id has no name");
  }

  private static void entityTypesResolveByName() {
    // 1.14 inserted `cat` at index 6, so almost every id moves.
    int cow404 = 9;
    require("minecraft:cow".equals(entityName(404, cow404)), "404 index 9 is a cow");
    int cow477 = EntityTypeMaps.translateRegistry(404, 477, cow404).getAsInt();
    require("minecraft:cow".equals(entityName(477, cow477)), "still a cow on 477");
    require(cow477 != cow404, "cow's index actually moved");
    require(EntityTypeMaps.translateRegistry(477, 404, cow477).getAsInt() == cow404,
        "cow round-trips");

    int shifted = 0;
    for (int type = 0; type < 95; type++) {
      var mapped = EntityTypeMaps.translateRegistry(404, 477, type);
      require(mapped.isPresent(), "every 404 entity type maps to 477: " + type);
      if (mapped.getAsInt() != type) shifted++;
      require(EntityTypeMaps.translateRegistry(477, 404, mapped.getAsInt()).getAsInt() == type,
          "entity type round-trips: " + type);
    }
    require(shifted == 89, "89 of 95 entity ids shift; got " + shifted);

    // A 1.14-only mob must fail closed rather than spawning something else.
    int cat477 = 6;
    require(EntityTypeMaps.translateRegistry(477, 404, cat477).isEmpty(),
        "1.14-only cat has no 1.13.2 counterpart");
  }

  private static String entityName(int protocol, int type) {
    // Resolve through the pair map: identity on the same protocol.
    return EntityTypeMaps.translateRegistry(protocol, protocol, type) == null ? null
        : NAMES.get(protocol).get(type);
  }

  private static final java.util.Map<Integer, List<String>> NAMES = loadEntityNames();

  private static java.util.Map<Integer, List<String>> loadEntityNames() {
    java.util.Map<Integer, List<String>> names = new java.util.HashMap<>();
    for (int protocol : new int[] { 404, 477 }) {
      List<String> list = new ArrayList<>();
      try (var in = Phase22_404_477_SemanticTests.class.getResourceAsStream(
          "/gg/tame/conduit/protocol/entity/entitytypes_" + protocol + "_names.txt")) {
        var reader = new java.io.BufferedReader(
            new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
          list.add(line.trim());
        }
      } catch (Exception exception) {
        throw new IllegalStateException("cannot load entity names for " + protocol, exception);
      }
      names.put(protocol, list);
    }
    return names;
  }

  /**
   * 1.14 inserted Entity.pose at 6 and LivingEntity.sleepingPos after the arrow
   * count. Metadata type ids did not change, so a renumbering here would be a
   * bug, not a translation.
   */
  private static void metadataIndexShift() throws Exception {
    require(MetadataCodec.mapIndexTo114(0, false) == 0, "entity base index unchanged");
    require(MetadataCodec.mapIndexTo114(5, true) == 5, "entity base index unchanged (living)");
    require(MetadataCodec.mapIndexTo114(6, false) == 7, "non-living shifts past pose");
    require(MetadataCodec.mapIndexTo114(6, true) == 7, "living shifts past pose");
    require(MetadataCodec.mapIndexTo114(11, true) == 13, "living shifts past sleepingPos too");
    require(MetadataCodec.mapIndexFrom114(6, false) == -1, "pose has no 1.13.2 field");
    require(MetadataCodec.mapIndexFrom114(12, true) == -1, "sleepingPos has no 1.13.2 field");
    require(MetadataCodec.mapIndexFrom114(13, true) == 11, "living index comes back");

    // A health float on a living entity: index moves, type id does not.
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(body);
    out.writeByte(7);                       // 1.13.2 LivingEntity health
    MinecraftOutput.varInt(out, 2);         // Float
    out.writeFloat(18.5f);
    out.writeByte(0xff);

    byte[] translated = MetadataCodec.translate(ProtocolDefinition.forVersion(404),
        ProtocolDefinition.forVersion(477), body.toByteArray(), true);
    DataInputStream in = new DataInputStream(new ByteArrayInputStream(translated));
    require(in.readUnsignedByte() == 8, "health index shifted 7 → 8");
    require(MinecraftInput.varInt(in) == 2, "Float is still type 2 on 1.14");
    require(in.readFloat() == 18.5f, "health value survives");
  }

  /** A chunk carrying a real 1.13.2 palette must arrive as the same blocks. */
  private static void chunkTranslationRemapsPalette() throws Exception {
    int stairs404 = 1660;   // oak_stairs, one concrete state well above the divergence point
    int[] states = new int[4096];
    java.util.Arrays.fill(states, 0);
    states[0] = 1;          // stone
    states[1] = stairs404;
    states[2] = 2389;

    SemanticChunkSection section = new SemanticChunkSection(3, states, new int[0],
        BlockStateMaps.emptyLight(), BlockStateMaps.emptyLight());
    SemanticChunk chunk = new SemanticChunk(3, -5, true,
        List.of(new SemanticChunk.SectionSlot(0, section)),
        new int[1024], List.of(), new byte[0]);

    byte[] as404 = ChunkCodec393.encodeLegacy(chunk);
    byte[] packet = PlayPackets.withId(ProtocolDefinition.forVersion(404)
        .id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_CHUNK_DATA), as404);

    Protocol404To477Translator translator = Protocol404To477Translator.client477();
    byte[] translated = translator.backendToClient(ConnectionState.PLAY, packet);
    require(translated != null, "chunk translated");

    SemanticChunk decoded = ChunkCodec477.decode(PlayPackets.body(translated));
    int[] arrived = decoded.sections().get(0).section().blockStates();
    require(arrived[0] == BlockStateMaps.translate(404, 477, 1), "stone remapped, not copied");
    require(arrived[1] == BlockStateMaps.translate(404, 477, stairs404), "stairs remapped");
    require(arrived[1] != stairs404, "stairs id actually changed");
    require(arrived[2] == BlockStateMaps.translate(404, 477, 2389), "third state remapped");
    require(decoded.chunkX() == 3 && decoded.chunkZ() == -5, "coordinates preserved");

    // And the light/view packets 1.14 requires are produced alongside.
    List<byte[]> queued = translator.drainToClient();
    require(queued.size() == 3, "update light + view distance + view position queued");
  }

  /**
   * 1.14 prefixes each chunk section with its non-air block count. Conduit had
   * neither written nor read it, which round-trips perfectly against itself and
   * desynchronises every real 1.14 client — the client reported it as an
   * oversized NBT LongArray and an out-of-range section index, both of them
   * symptoms several fields downstream of the actual fault.
   *
   * <p>So this test reads the encoded bytes directly rather than through the
   * decoder, because a decoder with the same omission would agree with a wrong
   * encoder.
   */
  private static void chunkSectionCarriesBlockCount() throws Exception {
    int[] states = new int[4096];
    states[0] = 1;
    states[1] = 1;
    states[2] = 1660;
    SemanticChunkSection section = new SemanticChunkSection(3, states, new int[0], new byte[0], new byte[0]);
    SemanticChunk chunk = new SemanticChunk(0, 0, true,
        List.of(new SemanticChunk.SectionSlot(0, section)), new int[256], List.of(), new byte[0]);

    byte[] encoded = ChunkCodec477.encode(chunk);
    DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
    in.readInt();                            // x
    in.readInt();                            // z
    in.readBoolean();                        // fullChunk
    MinecraftInput.varInt(in);               // bitMap
    require(in.readUnsignedByte() == 10, "heightmaps is a TAG_Compound");
    require(in.readUnsignedShort() == 0, "with an empty name");
    require(in.readUnsignedByte() == 0, "and no entries");
    MinecraftInput.varInt(in);               // data length

    require(in.readShort() == 3, "section begins with its non-air block count");
    int bits = in.readUnsignedByte();
    require(bits >= 4 && bits <= 8, "then bitsPerBlock; got " + bits);
  }

  /**
   * Spawn Mob and Spawn Player carry a trailing metadata block. Forwarding it
   * raw lands a 1.13.2 byte field on 1.14's index 6, which is Pose, and the
   * client dies with ClassCastException the moment the entity ticks.
   */
  private static void spawnMobTranslatesTrailingMetadata() throws Exception {
    int cow404 = 9;
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(body);
    MinecraftOutput.varInt(out, 4242);                  // entity id
    out.writeLong(1L); out.writeLong(2L);               // uuid
    MinecraftOutput.varInt(out, cow404);                // type
    out.writeDouble(1); out.writeDouble(64); out.writeDouble(2);
    out.writeByte(0); out.writeByte(0); out.writeByte(0);
    out.writeShort(0); out.writeShort(0); out.writeShort(0);
    out.writeByte(6);                                   // 1.13.2 index 6: LivingEntity hand states
    MinecraftOutput.varInt(out, 0);                     // Byte
    out.writeByte(1);
    out.writeByte(0xff);                                // terminator

    byte[] packet = PlayPackets.withId(ProtocolDefinition.forVersion(404).id(
        ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SPAWN_LIVING_ENTITY),
        body.toByteArray());
    byte[] translated = Protocol404To477Translator.client477()
        .backendToClient(ConnectionState.PLAY, packet);
    require(translated != null, "spawn mob translated");

    DataInputStream in = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(translated)));
    require(MinecraftInput.varInt(in) == 4242, "entity id preserved");
    in.readLong(); in.readLong();
    int type = MinecraftInput.varInt(in);
    require(type == EntityTypeMaps.translateRegistry(404, 477, cow404).getAsInt(),
        "cow resolved by name");
    require(type != cow404, "cow's registry index moved in 1.14");
    in.readNBytes(3 * 8 + 3 + 3 * 2);
    int index = in.readUnsignedByte();
    require(index == 7, "hand states moved off 1.14's Pose index 6, to 7; got " + index);
    require(MinecraftInput.varInt(in) == 0, "still a Byte");
  }

  private static void blockUpdateRemapsState() throws Exception {
    ProtocolDefinition v404 = ProtocolDefinition.forVersion(404);
    int state404 = 1660;
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(body);
    out.writeLong(packLegacy(10, 70, -3));
    MinecraftOutput.varInt(out, state404);

    byte[] packet = PlayPackets.withId(v404.id(ConnectionState.PLAY,
        PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_BLOCK_UPDATE), body.toByteArray());
    byte[] translated = Protocol404To477Translator.client477()
        .backendToClient(ConnectionState.PLAY, packet);

    DataInputStream in = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(translated)));
    in.readLong();
    int arrived = MinecraftInput.varInt(in);
    require(arrived == BlockStateMaps.translate(404, 477, state404), "block update state remapped");
    require(arrived != state404, "block update state actually changed");
  }

  private static long packLegacy(int x, int y, int z) {
    return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
  }

  /** {display:{Name,Lore},Enchantments:[{id,lvl}],Damage:42} as network NBT. */
  private static byte[] enchantedSwordTag() throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    // TAG_Compound body (the root tag byte and name are written by ItemNbt).
    out.writeByte(10);                                  // TAG_Compound: display
    writeName(out, "display");
    out.writeByte(8);                                   // TAG_String: Name
    writeName(out, "Name");
    writeString(out, "{\"text\":\"Widowmaker\"}");
    out.writeByte(9);                                   // TAG_List: Lore
    writeName(out, "Lore");
    out.writeByte(8);                                   // of TAG_String
    out.writeInt(1);
    writeString(out, "{\"text\":\"Forged in testing\"}");
    out.writeByte(0);                                   // end display

    out.writeByte(9);                                   // TAG_List: Enchantments
    writeName(out, "Enchantments");
    out.writeByte(10);                                  // of TAG_Compound
    out.writeInt(1);
    out.writeByte(8);
    writeName(out, "id");
    writeString(out, "minecraft:sharpness");
    out.writeByte(2);                                   // TAG_Short: lvl
    writeName(out, "lvl");
    out.writeShort(5);
    out.writeByte(0);                                   // end enchantment entry

    out.writeByte(3);                                   // TAG_Int: Damage
    writeName(out, "Damage");
    out.writeInt(42);

    out.writeByte(0);                                   // end root
    out.flush();
    return buffer.toByteArray();
  }

  private static void writeName(DataOutputStream out, String name) throws Exception {
    byte[] bytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    out.writeShort(bytes.length);
    out.write(bytes);
  }

  private static void writeString(DataOutputStream out, String value) throws Exception {
    writeName(out, value);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
