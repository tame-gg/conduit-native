package gg.tame.conduit.tests;

import gg.tame.conduit.login.ProfileProperty;
import gg.tame.conduit.protocol.CompatibilityCompleteness;
import gg.tame.conduit.protocol.CompatibilityRegistry;
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
import gg.tame.conduit.protocol.chunk.ChunkCodec393;
import gg.tame.conduit.protocol.chunk.ChunkCodec765;
import gg.tame.conduit.protocol.chunk.SemanticChunk;
import gg.tame.conduit.protocol.chunk.SemanticChunkSection;
import gg.tame.conduit.protocol.codec.BlockPositionCodec;
import gg.tame.conduit.protocol.codec.PlayerInfoCodec;
import gg.tame.conduit.protocol.semantic.SemanticPlayerInfo;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Phase 18 — 393↔765 world entry: player info, chunks, palettes, height policy. */
public final class Phase18_393_765_WorldTests {
  private Phase18_393_765_WorldTests() {}

  public static void run() throws Exception {
    blockStateMaps();
    playerInfoRoundTrip();
    playerInfoTranslate765to393();
    chunkSectionPalette();
    chunk765to393HeightPolicy();
    chunkTranslateThroughTranslator();
    updateLightDropped();
    setCompressionDroppedToward393();
    heldItemTranslated();
    declareRecipesDroppedToward393();
    serverDataDroppedToward393();
    worldBorderInitTranslatedToward393();
    updateTimeRemappedToward393();
    setTickingStateDroppedToward393();
    stepTickDroppedToward393();
    containerContentWithheldFrom393();
    containerSlotWithheldFrom393();
    declareCommandsWithheldFrom393();
    entityMetadataWithheldFrom393();
    entityAttributesWithheldFrom393();
    advancementsDroppedToward393();
    updateHealthRemappedToward393();
    setExperienceRemappedToward393();
    blockPositionEraRepack();
    blockUpdateTranslatedToward393();
    spawnPositionRepackedToward393();
    clientInformationExtendedToward765();
    chatEnvelopeSynthesizedToward765();
    playerDiggingTranslatedToward765();
    multiBlockChangeTranslatedToward393();
    swingArmRemappedToward765();
    difficultyTrimmedToward393();
    entityStatusRemapped();
    malformedChunkRejected();
    compatibilityStillPartial();
    System.out.println("Phase18_393_765_WorldTests passed.");
  }

  private static void blockStateMaps() {
    require(BlockStateMaps.to393(0) == 0, "air");
    require(BlockStateMaps.to393(1) == 1, "stone");
    require(BlockStateMaps.to765(0) == 0, "air reverse");
    require(BlockStateMaps.plainsBiome113() == 1, "plains");
  }

  private static void playerInfoRoundTrip() throws Exception {
    ProtocolDefinition v393 = ProtocolDefinition.forVersion(393);
    UUID id = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    SemanticPlayerInfo info = new SemanticPlayerInfo(PacketDirection.SERVER_TO_CLIENT,
        SemanticPlayerInfo.Action.ADD_PLAYER,
        List.of(new SemanticPlayerInfo.Entry(id, Optional.of("Steve"),
            List.of(new ProfileProperty("textures", "value", Optional.empty())),
            Optional.of(1), Optional.of(50), Optional.empty(), Optional.of(true))));
    byte[] encoded = PlayerInfoCodec.encode(v393, info);
    SemanticPlayerInfo decoded = PlayerInfoCodec.decode(v393, encoded);
    require(decoded.action() == SemanticPlayerInfo.Action.ADD_PLAYER, "action");
    require(decoded.entries().get(0).username().orElse("").equals("Steve"), "name");
    require(decoded.entries().get(0).properties().size() == 1, "textures");
  }

  private static void playerInfoTranslate765to393() throws Exception {
    ProtocolDefinition v765 = ProtocolDefinition.forVersion(765);
    UUID id = UUID.randomUUID();
    SemanticPlayerInfo info = new SemanticPlayerInfo(PacketDirection.SERVER_TO_CLIENT,
        SemanticPlayerInfo.Action.ADD_PLAYER,
        List.of(new SemanticPlayerInfo.Entry(id, Optional.of("Alex"), List.of(),
            Optional.of(0), Optional.of(10), Optional.empty(), Optional.of(true))));
    byte[] modern = PlayerInfoCodec.encode(v765, info);
    ProtocolTranslator t = Translators.forPair(393, 765);
    byte[] legacy = t.backendToClient(ConnectionState.PLAY, modern);
    require(PlayPackets.packetId(legacy) == 0x30, "393 player_info id");
    SemanticPlayerInfo decoded = PlayerInfoCodec.decode(ProtocolDefinition.forVersion(393), legacy);
    require(decoded.entries().get(0).uuid().equals(id), "uuid preserved");
    require(decoded.entries().get(0).username().orElse("").equals("Alex"), "name preserved");
  }

  private static void chunkSectionPalette() throws Exception {
    int[] states = new int[4096];
    java.util.Arrays.fill(states, 1); // stone
    states[0] = 0;
    SemanticChunkSection section = new SemanticChunkSection(4095, states, new int[0],
        BlockStateMaps.emptyLight(), BlockStateMaps.emptyLight());
    SemanticChunk chunk = new SemanticChunk(0, 0, true,
        List.of(new SemanticChunk.SectionSlot(0, section)), new int[1024], List.of(), new byte[0]);
    byte[] body = ChunkCodec393.encodeLegacy(chunk);
    SemanticChunk decoded = ChunkCodec393.decode(body);
    require(decoded.sections().size() == 1, "one section");
    require(decoded.sections().get(0).section().blockStates()[1] == 1, "stone roundtrip");
  }

  private static void chunk765to393HeightPolicy() throws Exception {
    int[] stone = new int[4096];
    java.util.Arrays.fill(stone, 1);
    List<SemanticChunk.SectionSlot> sections = List.of(
        new SemanticChunk.SectionSlot(-1, new SemanticChunkSection(4096, stone, new int[64], new byte[0], new byte[0])),
        new SemanticChunk.SectionSlot(0, new SemanticChunkSection(4096, stone, new int[64], new byte[0], new byte[0])),
        new SemanticChunk.SectionSlot(16, new SemanticChunkSection(4096, stone, new int[64], new byte[0], new byte[0]))
    );
    SemanticChunk modern = new SemanticChunk(2, 3, true, sections, new int[0], List.of(), new byte[0]);
    SemanticChunk legacy = modern.toLegacy113();
    require(legacy.sections().size() == 1, "only Y0..15 kept");
    require(legacy.sections().get(0).sectionY() == 0, "section 0");
  }

  private static void chunkTranslateThroughTranslator() throws Exception {
    int[] stone765 = new int[4096];
    java.util.Arrays.fill(stone765, BlockStateMaps.to765(1));
    SemanticChunk modern = new SemanticChunk(1, 1, true,
        List.of(new SemanticChunk.SectionSlot(0,
            new SemanticChunkSection(4096, stone765, new int[64], BlockStateMaps.emptyLight(), BlockStateMaps.emptyLight()))),
        new int[0], List.of(), new byte[0]);
    byte[] body765 = ChunkCodec765.encode(modern);
    byte[] packet765 = PlayPackets.withId(
        ProtocolDefinition.forVersion(765).id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_CHUNK_DATA),
        body765);
    ProtocolTranslator t = Translators.forPair(393, 765);
    byte[] packet393 = t.backendToClient(ConnectionState.PLAY, packet765);
    require(PlayPackets.packetId(packet393) == 0x22, "393 chunk id");
    SemanticChunk decoded = ChunkCodec393.decode(PlayPackets.body(packet393));
    require(decoded.chunkX() == 1 && decoded.chunkZ() == 1, "coords");
    require(!decoded.sections().isEmpty(), "has section");
  }

  private static void updateLightDropped() throws Exception {
    ProtocolTranslator t = Translators.forPair(393, 765);
    byte[] light = PlayPackets.withId(
        ProtocolDefinition.forVersion(765).id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_UPDATE_LIGHT),
        new byte[] {0, 0, 0, 0, 0, 0, 0, 0});
    byte[] out = t.backendToClient(ConnectionState.PLAY, light);
    require(out == null, "light dropped");
  }

  /** Regression: real 1.13 client failed when Paper Set Compression was forwarded (commit 764468b). */
  private static void setCompressionDroppedToward393() throws Exception {
    ProtocolTranslator t = Translators.forPair(393, 765);
    byte[] compression = PlayPackets.withId(
        ProtocolDefinition.forVersion(765).id(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_SET_COMPRESSION),
        new byte[] {(byte) 0x80, 0x02}); // VarInt 256 threshold
    byte[] out = t.backendToClient(ConnectionState.LOGIN, compression);
    require(out == null, "set compression must not reach 393 client");
  }

  /** Regression: real Paper 1.20.4 sent held_item_slot (0x51) immediately after Join Game. */
  private static void heldItemTranslated() throws Exception {
    ProtocolTranslator t = Translators.forPair(393, 765);
    byte[] modern = PlayPackets.withId(0x51, new byte[] {0});
    byte[] legacy = t.backendToClient(ConnectionState.PLAY, modern);
    require(legacy != null && PlayPackets.packetId(legacy) == 0x3D, "held item id 393");
    require(PlayPackets.body(legacy).length == 1 && PlayPackets.body(legacy)[0] == 0, "slot preserved");
  }

  /** Regression: Paper 1.20.4 sends declare_recipes (0x73) before chunks; schema incompatible with 393. */
  private static void declareRecipesDroppedToward393() throws Exception {
    ProtocolTranslator t = Translators.forPair(393, 765);
    require(t.backendToClient(ConnectionState.PLAY, PlayPackets.withId(0x73, new byte[] {0})) == null, "recipes dropped");
    require(t.backendToClient(ConnectionState.PLAY, PlayPackets.withId(0x74, new byte[] {0})) == null, "tags dropped");
    require(t.backendToClient(ConnectionState.PLAY, PlayPackets.withId(0x52, new byte[] {0, 0})) == null, "view pos dropped");
    require(t.backendToClient(ConnectionState.PLAY, PlayPackets.withId(0x53, new byte[] {0})) == null, "view dist dropped");
  }

  /** Regression: 765 difficulty includes locked bool; 393 is difficulty byte only. */
  private static void difficultyTrimmedToward393() throws Exception {
    ProtocolTranslator t = Translators.forPair(393, 765);
    byte[] modern = PlayPackets.withId(0x0B, new byte[] {2, 1}); // normal + locked
    byte[] legacy = t.backendToClient(ConnectionState.PLAY, modern);
    require(legacy != null && PlayPackets.packetId(legacy) == 0x0D, "393 difficulty id");
    require(PlayPackets.body(legacy).length == 1 && PlayPackets.body(legacy)[0] == 2, "locked bit stripped");
  }

  /** Regression: Paper sent entity_status (0x1d) during world entry; body matches 393 0x1c. */
  private static void entityStatusRemapped() throws Exception {
    ProtocolTranslator t = Translators.forPair(393, 765);
    byte[] modern = PlayPackets.withId(0x1D, new byte[] {0, 0, 0, 1, 24});
    byte[] legacy = t.backendToClient(ConnectionState.PLAY, modern);
    require(legacy != null && PlayPackets.packetId(legacy) == 0x1C, "393 entity_status id");
  }

  /**
   * Regression: Paper sent server_data (765 0x49) right after Synchronize Player Position.
   * Body below is the real captured wire form: NBT TAG_String MOTD, no icon, secure chat off.
   * 393 has no server_data (added in 1.19), so it must be dropped rather than reach the client.
   */
  private static void serverDataDroppedToward393() throws Exception {
    ProtocolTranslator t = Translators.forPair(393, 765);
    byte[] motd = "Conduit 765 backend".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
    body.write(0x08);                       // NBT TAG_String
    body.write(0x00);                       // length high byte
    body.write(motd.length);                // length low byte (19)
    body.write(motd, 0, motd.length);
    body.write(0x00);                       // icon present = false
    body.write(0x00);                       // enforces secure chat = false
    byte[] modern = PlayPackets.withId(0x49, body.toByteArray());
    require(t.backendToClient(ConnectionState.PLAY, modern) == null, "server data dropped");
  }

  /**
   * Regression: Paper sent Initialize World Border (765 0x23) during world entry.
   * Bytes below are the real captured wire form (vanilla defaults: diameter 59999968,
   * portal boundary 29999984, warningBlocks 5, warningTime 15).
   *
   * <p>393 carries the border behind an action enum and orders warningTime before
   * warningBlocks, so a byte-for-byte forward would silently swap 5 and 15.
   */
  private static void worldBorderInitTranslatedToward393() throws Exception {
    ProtocolTranslator t = Translators.forPair(393, 765);
    java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
    java.io.DataOutputStream out = new java.io.DataOutputStream(body);
    out.writeDouble(0.0);                    // center X
    out.writeDouble(0.0);                    // center Z
    out.writeDouble(59999968.0);             // old diameter
    out.writeDouble(59999968.0);             // new diameter
    MinecraftOutput.varLong(out, 0L);        // speed
    MinecraftOutput.varInt(out, 29999984);   // portal teleport boundary
    MinecraftOutput.varInt(out, 5);          // warning blocks
    MinecraftOutput.varInt(out, 15);         // warning time
    byte[] modern = PlayPackets.withId(0x23, body.toByteArray());

    byte[] legacy = t.backendToClient(ConnectionState.PLAY, modern);
    require(legacy != null, "world border translated");
    require(PlayPackets.packetId(legacy) == 0x3B, "393 world border id");

    java.io.DataInputStream in =
        new java.io.DataInputStream(new java.io.ByteArrayInputStream(PlayPackets.body(legacy)));
    require(MinecraftInput.varInt(in) == 3, "393 INITIALIZE action");
    require(in.readDouble() == 0.0, "center x");
    require(in.readDouble() == 0.0, "center z");
    require(in.readDouble() == 59999968.0, "old diameter");
    require(in.readDouble() == 59999968.0, "new diameter");
    require(MinecraftInput.varLong(in) == 0L, "speed");
    require(MinecraftInput.varInt(in) == 29999984, "portal boundary");
    // The swap guard: 393 emits warningTime first, then warningBlocks.
    require(MinecraftInput.varInt(in) == 15, "393 warning time first");
    require(MinecraftInput.varInt(in) == 5, "393 warning blocks second");
    require(in.available() == 0, "no trailing bytes");
  }

  /**
   * Regression: Paper sent Update Time (765 0x62) during world entry. Layout is two big-endian
   * longs on both eras, so only the id changes — but the values must survive intact.
   */
  private static void updateTimeRemappedToward393() throws Exception {
    ProtocolTranslator t = Translators.forPair(393, 765);
    java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
    java.io.DataOutputStream out = new java.io.DataOutputStream(body);
    out.writeLong(47391L);  // world age, as captured
    out.writeLong(47391L);  // time of day, as captured
    byte[] legacy = t.backendToClient(ConnectionState.PLAY, PlayPackets.withId(0x62, body.toByteArray()));
    require(legacy != null && PlayPackets.packetId(legacy) == 0x4A, "393 update_time id");
    java.io.DataInputStream in =
        new java.io.DataInputStream(new java.io.ByteArrayInputStream(PlayPackets.body(legacy)));
    require(in.readLong() == 47391L, "world age preserved");
    require(in.readLong() == 47391L, "time of day preserved");
    require(in.available() == 0, "no trailing bytes");
  }

  /**
   * Regression: Paper sent Set Ticking State (765 0x6e) during world entry — captured body was
   * tick rate 20.0f + frozen false. Added in 1.20.3, so 393 must never see it.
   */
  private static void setTickingStateDroppedToward393() throws Exception {
    ProtocolTranslator t = Translators.forPair(393, 765);
    java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
    java.io.DataOutputStream out = new java.io.DataOutputStream(body);
    out.writeFloat(20.0f);  // tick rate, as captured
    out.writeBoolean(false); // is frozen
    byte[] modern = PlayPackets.withId(0x6E, body.toByteArray());
    require(t.backendToClient(ConnectionState.PLAY, modern) == null, "ticking state dropped");
  }

  /**
   * Regression: Paper sent Step Tick (765 0x6f, captured body = VarInt 0) right after
   * Set Ticking State. Added in 1.20.3 alongside it; 393 has no equivalent.
   */
  private static void stepTickDroppedToward393() throws Exception {
    ProtocolTranslator t = Translators.forPair(393, 765);
    byte[] modern = PlayPackets.withId(0x6F, new byte[] {0});
    require(t.backendToClient(ConnectionState.PLAY, modern) == null, "step tick dropped");
  }

  /**
   * Regression: Paper sent Set Container Content (765 0x13) during world entry — captured body was
   * window 0, state 1, 46 empty slots plus an empty carried item.
   *
   * <p>Withheld rather than translated: Slot payloads carry era-specific item registry ids and
   * Conduit has no verified 1.13 mapping, so a translation could hand the client wrong items.
   */
  private static void containerContentWithheldFrom393() throws Exception {
    ProtocolTranslator t = Translators.forPair(393, 765);
    java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
    body.write(0x00);        // window id: player inventory
    body.write(0x01);        // state id
    body.write(0x2E);        // slot count = 46
    for (int slot = 0; slot < 47; slot++) body.write(0x00); // 46 empty slots + empty carried item
    byte[] modern = PlayPackets.withId(0x13, body.toByteArray());
    require(t.backendToClient(ConnectionState.PLAY, modern) == null, "container content withheld");
  }

  /**
   * Regression: Paper sent Set Container Slot (765 0x15) during world entry — captured body was
   * window 0, state 2, slot 45 (offhand), empty item. Withheld for the same item-registry reason.
   */
  private static void containerSlotWithheldFrom393() throws Exception {
    ProtocolTranslator t = Translators.forPair(393, 765);
    byte[] modern = PlayPackets.withId(0x15, new byte[] {0x00, 0x02, 0x00, 0x2D, 0x00});
    require(t.backendToClient(ConnectionState.PLAY, modern) == null, "container slot withheld");
  }

  /**
   * Regression: Paper sent Commands (765 0x11) during world entry. The brigadier tree cannot be
   * forwarded (393 names argument parsers by string, 1.19+ by registry id), but withholding it
   * must drop the packet rather than raise and tear down the session.
   */
  private static void declareCommandsWithheldFrom393() throws Exception {
    ProtocolTranslator t = Translators.forPair(393, 765);
    // Minimal tree: one node, root index 0.
    byte[] modern = PlayPackets.withId(0x11, new byte[] {0x01, 0x00, 0x00, 0x00});
    require(t.backendToClient(ConnectionState.PLAY, modern) == null, "command tree withheld");
  }

  /**
   * Regression: Paper sent Set Entity Metadata (765 0x56) during world entry — captured body was
   * entity 1083, index 9, type 3 (Float on 765), value 20.0, then the 0xff terminator.
   *
   * <p>Must be withheld: 1.19 inserted VarLong at type id 2, so 765 type 3 (Float) is type 3
   * (String) on 393. Forwarding the bytes would desync the client mid-packet.
   */
  private static void entityMetadataWithheldFrom393() throws Exception {
    ProtocolTranslator t = Translators.forPair(393, 765);
    java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
    java.io.DataOutputStream out = new java.io.DataOutputStream(body);
    MinecraftOutput.varInt(out, 1083); // entity id, as captured
    out.writeByte(9);                  // metadata index
    MinecraftOutput.varInt(out, 3);    // type: Float on 765, String on 393
    out.writeFloat(20.0f);             // health
    out.writeByte(0xFF);               // end of metadata
    byte[] modern = PlayPackets.withId(0x56, body.toByteArray());
    require(t.backendToClient(ConnectionState.PLAY, modern) == null, "entity metadata withheld");
  }

  /**
   * Regression: Paper sent Update Attributes (765 0x71) during world entry — captured body began
   * entity 1084, 2 attributes, "minecraft:generic.movement_speed" = 0.1, 0 modifiers.
   * Withheld: 393 uses an Int count and pre-1.16 attribute key names.
   */
  private static void entityAttributesWithheldFrom393() throws Exception {
    ProtocolTranslator t = Translators.forPair(393, 765);
    java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
    java.io.DataOutputStream out = new java.io.DataOutputStream(body);
    MinecraftOutput.varInt(out, 1084);  // entity id, as captured
    MinecraftOutput.varInt(out, 1);     // attribute count
    MinecraftOutput.string(out, "minecraft:generic.movement_speed");
    out.writeDouble(0.10000000149011612d);
    MinecraftOutput.varInt(out, 0);     // no modifiers
    byte[] modern = PlayPackets.withId(0x71, body.toByteArray());
    require(t.backendToClient(ConnectionState.PLAY, modern) == null, "attributes withheld");
  }

  /**
   * Regression: Paper sent Update Advancements (765 0x70) during world entry — captured body began
   * reset=true, 2 mappings, "minecraft:recipes/decorations/crafting_table".
   * Dropped: advancement display/criteria structure diverged after 1.13, and none of it is
   * required to enter or render the world.
   */
  private static void advancementsDroppedToward393() throws Exception {
    ProtocolTranslator t = Translators.forPair(393, 765);
    java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
    java.io.DataOutputStream out = new java.io.DataOutputStream(body);
    out.writeBoolean(true);          // reset/clear
    MinecraftOutput.varInt(out, 0);  // no advancement mappings
    MinecraftOutput.varInt(out, 0);  // no removals
    MinecraftOutput.varInt(out, 0);  // no progress
    byte[] modern = PlayPackets.withId(0x70, body.toByteArray());
    require(t.backendToClient(ConnectionState.PLAY, modern) == null, "advancements dropped");
  }

  /**
   * Regression: Paper sent Set Health (765 0x5b) during world entry — captured body was
   * health 20.0, food 20, saturation 5.0. Layout is identical on 393, so only the id changes.
   */
  private static void updateHealthRemappedToward393() throws Exception {
    ProtocolTranslator t = Translators.forPair(393, 765);
    java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
    java.io.DataOutputStream out = new java.io.DataOutputStream(body);
    out.writeFloat(20.0f);          // health
    MinecraftOutput.varInt(out, 20); // food
    out.writeFloat(5.0f);           // saturation
    byte[] legacy = t.backendToClient(ConnectionState.PLAY, PlayPackets.withId(0x5B, body.toByteArray()));
    require(legacy != null && PlayPackets.packetId(legacy) == 0x44, "393 update_health id");
    java.io.DataInputStream in =
        new java.io.DataInputStream(new java.io.ByteArrayInputStream(PlayPackets.body(legacy)));
    require(in.readFloat() == 20.0f, "health preserved");
    require(MinecraftInput.varInt(in) == 20, "food preserved");
    require(in.readFloat() == 5.0f, "saturation preserved");
    require(in.available() == 0, "no trailing bytes");
  }

  /**
   * Regression: Paper sent Set Experience (765 0x5a) during world entry — captured body was all
   * zeros (bar 0.0, level 0, total 0). Identical layout on 393, so only the id changes.
   */
  private static void setExperienceRemappedToward393() throws Exception {
    ProtocolTranslator t = Translators.forPair(393, 765);
    java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
    java.io.DataOutputStream out = new java.io.DataOutputStream(body);
    out.writeFloat(0.0f);           // experience bar
    MinecraftOutput.varInt(out, 0); // level
    MinecraftOutput.varInt(out, 0); // total experience
    byte[] legacy = t.backendToClient(ConnectionState.PLAY, PlayPackets.withId(0x5A, body.toByteArray()));
    require(legacy != null && PlayPackets.packetId(legacy) == 0x43, "393 set_experience id");
    require(PlayPackets.body(legacy).length == 6, "body preserved");
  }

  /** The 1.14 position field-order change: legacy packs x,y,z; modern packs x,z,y. */
  private static void blockPositionEraRepack() {
    ProtocolDefinition v393 = ProtocolDefinition.forVersion(393);
    ProtocolDefinition v765 = ProtocolDefinition.forVersion(765);
    // The real captured Block Update position.
    long modern = 0xFFFFF6800001C046L;
    BlockPositionCodec.BlockPosition decoded = BlockPositionCodec.unpack(v765, modern);
    require(decoded.x() == -38, "x");
    require(decoded.y() == 70, "y");
    require(decoded.z() == 28, "z");
    // Forwarding the raw long would have put the block at y=0, z=114758.
    BlockPositionCodec.BlockPosition naive = BlockPositionCodec.unpack(v393, modern);
    require(naive.y() != 70 || naive.z() != 28, "raw forward really is wrong");
    // Repacking preserves the coordinates.
    long legacy = BlockPositionCodec.translate(v765, v393, modern);
    BlockPositionCodec.BlockPosition round = BlockPositionCodec.unpack(v393, legacy);
    require(round.x() == -38 && round.y() == 70 && round.z() == 28, "repack preserves position");
    // And back again, including negative coordinates.
    require(BlockPositionCodec.translate(v393, v765, legacy) == modern, "round trip");
  }

  /**
   * Regression: Paper sent Block Update (765 0x09) once chunks were flowing — captured body was
   * position (-38, 70, 28) packed modern, state 10.
   */
  private static void blockUpdateTranslatedToward393() throws Exception {
    ProtocolTranslator t = Translators.forPair(393, 765);
    java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
    java.io.DataOutputStream out = new java.io.DataOutputStream(body);
    out.writeLong(0xFFFFF6800001C046L);
    MinecraftOutput.varInt(out, 10);
    byte[] legacy = t.backendToClient(ConnectionState.PLAY, PlayPackets.withId(0x09, body.toByteArray()));
    require(legacy != null && PlayPackets.packetId(legacy) == 0x0B, "393 block_change id");
    java.io.DataInputStream in =
        new java.io.DataInputStream(new java.io.ByteArrayInputStream(PlayPackets.body(legacy)));
    BlockPositionCodec.BlockPosition at =
        BlockPositionCodec.unpack(ProtocolDefinition.forVersion(393), in.readLong());
    require(at.x() == -38 && at.y() == 70 && at.z() == 28, "position repacked for 393");
    require(MinecraftInput.varInt(in) == BlockStateMaps.to393(10), "state mapped");
  }

  /**
   * Regression: spawn position was forwarded with its packed long untouched, which moved the
   * 1.13 client's compass to the wrong place. It must be repacked like any other position.
   */
  private static void spawnPositionRepackedToward393() throws Exception {
    ProtocolTranslator t = Translators.forPair(393, 765);
    ProtocolDefinition v765 = ProtocolDefinition.forVersion(765);
    long modern = BlockPositionCodec.pack(v765, new BlockPositionCodec.BlockPosition(-38, 70, 28));
    java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
    java.io.DataOutputStream out = new java.io.DataOutputStream(body);
    out.writeLong(modern);
    out.writeFloat(0f); // 765 angle, which 393 has no field for
    byte[] legacy = t.backendToClient(ConnectionState.PLAY, PlayPackets.withId(0x54, body.toByteArray()));
    require(legacy != null && PlayPackets.packetId(legacy) == 0x49, "393 spawn_position id");
    byte[] legacyBody = PlayPackets.body(legacy);
    require(legacyBody.length == 8, "angle dropped for 393");
    java.io.DataInputStream in =
        new java.io.DataInputStream(new java.io.ByteArrayInputStream(legacyBody));
    BlockPositionCodec.BlockPosition at =
        BlockPositionCodec.unpack(ProtocolDefinition.forVersion(393), in.readLong());
    require(at.x() == -38 && at.y() == 70 && at.z() == 28, "spawn position repacked");
  }

  /**
   * Regression: the real 1.13 client's Client Settings was forwarded verbatim to Paper, which
   * rejected it with "readerIndex(12) + length(1) exceeds writerIndex(12)" — 1.20.4 appends
   * enableTextFiltering and allowServerListings. The 393 body must be extended, not copied.
   */
  private static void clientInformationExtendedToward765() throws Exception {
    ProtocolTranslator t = Translators.forPair(393, 765);
    java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
    java.io.DataOutputStream out = new java.io.DataOutputStream(body);
    MinecraftOutput.string(out, "en_us");
    out.writeByte(12);              // view distance
    MinecraftOutput.varInt(out, 0); // chat mode: enabled
    out.writeBoolean(true);         // chat colors
    out.writeByte(0x7F);            // displayed skin parts
    MinecraftOutput.varInt(out, 1); // main hand: right
    byte[] legacy = PlayPackets.withId(0x04, body.toByteArray());
    require(PlayPackets.body(legacy).length + 1 == 12, "captured 393 packet really is 12 bytes");

    byte[] modern = t.clientToBackend(ConnectionState.PLAY, legacy);
    require(modern != null && PlayPackets.packetId(modern) == 0x09, "765 client_information id");
    java.io.DataInputStream in =
        new java.io.DataInputStream(new java.io.ByteArrayInputStream(PlayPackets.body(modern)));
    require(MinecraftInput.string(in, 64).equals("en_us"), "locale");
    require(in.readByte() == 12, "view distance");
    require(MinecraftInput.varInt(in) == 0, "chat mode");
    require(in.readBoolean(), "chat colors");
    require(in.readUnsignedByte() == 0x7F, "skin parts");
    require(MinecraftInput.varInt(in) == 1, "main hand");
    require(!in.readBoolean(), "text filtering defaulted off");
    require(in.readBoolean(), "server listings defaulted on");
    require(in.available() == 0, "no trailing bytes");
  }

  /**
   * Regression: the 1.13 client's Chat Message (bare string) was forwarded to Paper as a 765 Chat
   * Command, which rejected it with "readerIndex(4) + length(8) exceeds writerIndex(4)" — 765
   * expects a signing envelope (timestamp, salt, signatures, acknowledgements) after the text.
   */
  private static void chatEnvelopeSynthesizedToward765() throws Exception {
    ProtocolTranslator t = Translators.forPair(393, 765);

    // Plain chat -> 765 Chat Message (0x05), unsigned.
    java.io.ByteArrayOutputStream chat = new java.io.ByteArrayOutputStream();
    MinecraftOutput.string(new java.io.DataOutputStream(chat), "hi");
    byte[] modernChat = t.clientToBackend(ConnectionState.PLAY, PlayPackets.withId(0x02, chat.toByteArray()));
    require(modernChat != null && PlayPackets.packetId(modernChat) == 0x05, "765 chat id");
    java.io.DataInputStream in =
        new java.io.DataInputStream(new java.io.ByteArrayInputStream(PlayPackets.body(modernChat)));
    require(MinecraftInput.string(in, 256).equals("hi"), "message text");
    in.readLong();                        // timestamp
    require(in.readLong() == 0L, "salt");
    require(!in.readBoolean(), "unsigned");
    require(MinecraftInput.varInt(in) == 0, "message count");
    require(in.available() == 3, "acknowledged bitset is 3 bytes");

    // Leading slash -> 765 Chat Command (0x04), slash stripped.
    java.io.ByteArrayOutputStream cmd = new java.io.ByteArrayOutputStream();
    MinecraftOutput.string(new java.io.DataOutputStream(cmd), "/list");
    byte[] modernCmd = t.clientToBackend(ConnectionState.PLAY, PlayPackets.withId(0x02, cmd.toByteArray()));
    require(modernCmd != null && PlayPackets.packetId(modernCmd) == 0x04, "765 chat command id");
    java.io.DataInputStream cin =
        new java.io.DataInputStream(new java.io.ByteArrayInputStream(PlayPackets.body(modernCmd)));
    require(MinecraftInput.string(cin, 256).equals("list"), "slash stripped");
    cin.readLong();                         // timestamp
    require(cin.readLong() == 0L, "salt");
    require(MinecraftInput.varInt(cin) == 0, "no argument signatures");
    require(MinecraftInput.varInt(cin) == 0, "message count");
    require(cin.available() == 3, "acknowledged bitset is 3 bytes");
  }

  /**
   * Regression: the real 1.13 client sent Player Digging (393 0x18) once it could move — captured
   * body was status 0, position (-58, 103, -5) packed legacy, face 1, matching a player standing
   * at (-57.5, 104.0, -7.5). The position must be repacked for 765 and the 1.19 prediction
   * sequence synthesized.
   */
  private static void playerDiggingTranslatedToward765() throws Exception {
    ProtocolTranslator t = Translators.forPair(393, 765);
    long legacyPacked = 0xFFFFF1819FFFFFFBL;
    BlockPositionCodec.BlockPosition expected =
        BlockPositionCodec.unpack(ProtocolDefinition.forVersion(393), legacyPacked);
    require(expected.x() == -58 && expected.y() == 103 && expected.z() == -5, "captured dig position");

    java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
    java.io.DataOutputStream out = new java.io.DataOutputStream(body);
    MinecraftOutput.varInt(out, 0); // status: started digging
    out.writeLong(legacyPacked);
    out.writeByte(1);              // face: top
    byte[] modern = t.clientToBackend(ConnectionState.PLAY, PlayPackets.withId(0x18, body.toByteArray()));
    require(modern != null && PlayPackets.packetId(modern) == 0x21, "765 player_action id");

    java.io.DataInputStream in =
        new java.io.DataInputStream(new java.io.ByteArrayInputStream(PlayPackets.body(modern)));
    require(MinecraftInput.varInt(in) == 0, "status");
    BlockPositionCodec.BlockPosition at =
        BlockPositionCodec.unpack(ProtocolDefinition.forVersion(765), in.readLong());
    require(at.x() == -58 && at.y() == 103 && at.z() == -5, "position repacked for 765");
    require(in.readByte() == 1, "face");
    require(MinecraftInput.varInt(in) == 0, "sequence synthesized");
    require(in.available() == 0, "no trailing bytes");
  }

  /**
   * Regression: Paper sent Update Section Blocks (765 0x47) — captured body was section
   * (x=-5, z=6, y=1) with 2 records: state 2389 at local (14,11,5) and air at local (13,11,5).
   *
   * <p>393 Multi Block Change is structurally unrelated: Int chunk X/Z, then per record a
   * horizontal nibble-pair byte, an absolute Y byte and a VarInt state. Translated semantically.
   */
  private static void multiBlockChangeTranslatedToward393() throws Exception {
    ProtocolTranslator t = Translators.forPair(393, 765);
    java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
    java.io.DataOutputStream out = new java.io.DataOutputStream(body);
    long section = ((long) (-5 & 0x3FFFFF) << 42) | ((long) (6 & 0x3FFFFF) << 20) | 1L;
    out.writeLong(section);
    MinecraftOutput.varInt(out, 2);
    MinecraftOutput.varLong(out, ((long) 2389 << 12) | (14 << 8) | (11 << 4) | 5);
    MinecraftOutput.varLong(out, ((long) 0 << 12) | (13 << 8) | (11 << 4) | 5);

    byte[] legacy = t.backendToClient(ConnectionState.PLAY, PlayPackets.withId(0x47, body.toByteArray()));
    require(legacy != null && PlayPackets.packetId(legacy) == 0x0F, "393 multi_block_change id");

    java.io.DataInputStream in =
        new java.io.DataInputStream(new java.io.ByteArrayInputStream(PlayPackets.body(legacy)));
    require(in.readInt() == -5, "chunk x");
    require(in.readInt() == 6, "chunk z");
    require(MinecraftInput.varInt(in) == 2, "record count");

    // Absolute Y = sectionY 1 * 16 + localY 5 = 21, for both records.
    int horizontal = in.readUnsignedByte();
    require((horizontal >> 4) == 14 && (horizontal & 0xF) == 11, "first record local x/z");
    require(in.readUnsignedByte() == 21, "first record absolute y");
    require(MinecraftInput.varInt(in) == BlockStateMaps.to393(2389), "first record state mapped");

    horizontal = in.readUnsignedByte();
    require((horizontal >> 4) == 13 && (horizontal & 0xF) == 11, "second record local x/z");
    require(in.readUnsignedByte() == 21, "second record absolute y");
    require(MinecraftInput.varInt(in) == BlockStateMaps.to393(0), "second record air");
    require(in.available() == 0, "no trailing bytes");
  }

  /**
   * Regression: the real 1.13 client sent Animation / swing arm (393 0x27, hand 0) once it could
   * act in the world. Single Hand VarInt on both eras, so only the id changes.
   */
  private static void swingArmRemappedToward765() throws Exception {
    ProtocolTranslator t = Translators.forPair(393, 765);
    byte[] modern = t.clientToBackend(ConnectionState.PLAY, PlayPackets.withId(0x27, new byte[] {0}));
    require(modern != null && PlayPackets.packetId(modern) == 0x33, "765 swing_arm id");
    require(PlayPackets.body(modern).length == 1, "hand preserved");
    require(PlayPackets.body(modern)[0] == 0, "main hand");
  }

  private static void malformedChunkRejected() {
    try {
      ChunkCodec393.decode(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 1, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0x7f});
      throw new AssertionError("accepted malformed");
    } catch (Exception expected) {
      require(true, "rejected");
    }
  }

  private static void compatibilityStillPartial() {
    require(CompatibilityRegistry.resolve(393, 765).completeness() == CompatibilityCompleteness.PARTIAL, "partial");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
