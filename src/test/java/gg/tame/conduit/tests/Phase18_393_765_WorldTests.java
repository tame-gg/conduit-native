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
