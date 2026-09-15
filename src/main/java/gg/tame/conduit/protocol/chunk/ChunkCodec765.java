package gg.tame.conduit.protocol.chunk;

import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.NetworkNbt;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 1.20.4 (765) Chunk Data (+ light) codec.
 * Wire: x, z, heightmaps(NBT), chunkData buffer, blockEntities, light masks + arrays.
 * Sections use padded palettes + per-section biomes; light is outside section bodies.
 * Public layout: PrismarineJS minecraft-data 1.20.4 + wiki.vg Chunk Format.
 */
public final class ChunkCodec765 {
  private static final int MAX_DATA = 2 * 1024 * 1024;
  private static final int MAX_SECTIONS = 32;
  private static final int MAX_BLOCK_ENTITIES = 4096;

  private ChunkCodec765() {}

  public static SemanticChunk decode(byte[] body) throws IOException {
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
      int x = in.readInt();
      int z = in.readInt();
      ByteArrayOutputStream hm = new ByteArrayOutputStream();
      NetworkNbt.skip(in); // heightmaps consumed; we do not preserve NBT for 393
      int dataSize = MinecraftInput.varInt(in);
      if (dataSize < 0 || dataSize > MAX_DATA) throw new IOException("chunk data size " + dataSize);
      byte[] data = new byte[dataSize];
      in.readFully(data);
      int beCount = MinecraftInput.varInt(in);
      if (beCount < 0 || beCount > MAX_BLOCK_ENTITIES) throw new IOException("block entities " + beCount);
      for (int i = 0; i < beCount; i++) skipChunkBlockEntity(in);
      LightData light = readLight(in);

      List<SemanticChunk.SectionSlot> sections = decodeSections(data, light);
      return new SemanticChunk(x, z, true, sections, new int[0], List.of(), hm.toByteArray());
    }
  }

  private static List<SemanticChunk.SectionSlot> decodeSections(byte[] data, LightData light) throws IOException {
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
      List<SemanticChunk.SectionSlot> sections = new ArrayList<>();
      // Overworld default: 24 sections from -4..19
      for (int i = 0; i < SemanticChunk.MODERN_SECTION_COUNT; i++) {
        if (in.available() <= 0) break;
        if (sections.size() >= MAX_SECTIONS) throw new IOException("too many sections");
        int sectionY = SemanticChunk.MODERN_MIN_SECTION + i;
        SemanticChunkSection section = readSection(in);
        byte[] blockLight = light.blockLightForIndex(i);
        byte[] skyLight = light.skyLightForIndex(i);
        sections.add(new SemanticChunk.SectionSlot(sectionY,
            new SemanticChunkSection(section.nonAirCount(), section.blockStates(), section.biomes(), blockLight, skyLight)));
      }
      return sections;
    }
  }

  private static SemanticChunkSection readSection(DataInputStream in) throws IOException {
    // Notchian non-air counts can exceed 4096 (fluids / double-counting). Wire is still a short;
    // clamp for semantic use — do not fail the section.
    int blockCount = in.readShort() & 0xffff;
    if (blockCount > 4096) blockCount = 4096;
    int[] blocks = readPaletted(in, 4096, 8, 15);
    int[] biomes = readPaletted(in, 64, 3, 6);
    return new SemanticChunkSection(blockCount, blocks, biomes, new byte[0], new byte[0]);
  }

  /**
   * Paletted container matching Notchian 1.18+ network rules:
   * - bits 0 = single-valued (data length present, usually 0)
   * - bits in 1..maxIndirect = indirect palette
   * - otherwise direct registry ids at {@code directBits} (invalid BPEs rounded up)
   * - data-array length may be smaller than expected (client uses expected) or larger
   *   (client consumes declared longs then treats storage as empty/zero)
   */
  private static int[] readPaletted(DataInputStream in, int entries, int maxIndirectBits, int directBits)
      throws IOException {
    int rawBits = in.readUnsignedByte();
    if (rawBits > 32) throw new IOException("palette bits " + rawBits);
    if (rawBits == 0) {
      int single = MinecraftInput.varInt(in);
      consumeLongArray(in, 0); // length present; expected 0
      int[] out = new int[entries];
      java.util.Arrays.fill(out, single);
      return out;
    }

    int bits;
    boolean indirect;
    if (rawBits >= 1 && rawBits <= maxIndirectBits) {
      bits = rawBits;
      // Blocks: Notchian never uses 1-3 for block states; round up to 4.
      if (entries == 4096 && bits < 4) bits = 4;
      indirect = true;
    } else {
      bits = directBits;
      indirect = false;
    }

    int[] palette = null;
    if (indirect) {
      int paletteLen = MinecraftInput.varInt(in);
      if (paletteLen < 0 || paletteLen > Math.max(entries, 4096)) throw new IOException("palette len " + paletteLen);
      palette = new int[paletteLen];
      for (int i = 0; i < paletteLen; i++) palette[i] = MinecraftInput.varInt(in);
    }

    int valuesPerLong = 64 / bits;
    int expectedLongs = (entries + valuesPerLong - 1) / valuesPerLong;
    long[] data = consumeLongArray(in, expectedLongs);
    if (data.length == 0) {
      // Oversized declared length: Notchian discards → all zeros / palette[0]
      int[] out = new int[entries];
      if (palette != null && palette.length > 0) java.util.Arrays.fill(out, palette[0]);
      return out;
    }
    int[] indices = BlockStateMaps.unpack(data, bits, entries, true);
    if (palette == null) return indices;
    int[] out = new int[entries];
    for (int i = 0; i < entries; i++) {
      int idx = indices[i];
      out[i] = idx >= 0 && idx < palette.length ? palette[idx] : 0;
    }
    return out;
  }

  /**
   * Read a VarInt-prefixed long array.
   * Notchian {@code FriendlyByteBuf.readLongArray} always consumes exactly the declared count.
   * Oversized vs expected storage → discard values (empty result). Undersized → pad with zeros.
   */
  private static long[] consumeLongArray(DataInputStream in, int expectedLongs) throws IOException {
    int declared = MinecraftInput.varInt(in);
    if (declared < 0 || declared > 4096 + 64) throw new IOException("long array length " + declared);
    if (declared > expectedLongs && expectedLongs > 0) {
      for (int i = 0; i < declared; i++) in.readLong();
      return new long[0];
    }
    long[] data = new long[declared];
    for (int i = 0; i < declared; i++) data[i] = in.readLong();
    if (declared >= expectedLongs) return data;
    long[] padded = new long[expectedLongs];
    System.arraycopy(data, 0, padded, 0, declared);
    return padded;
  }

  public static byte[] encodeFromLegacy(SemanticChunk legacy393StatesAlready) throws IOException {
    SemanticChunk modern = legacy393StatesAlready.toModern765();
    return encode(modern);
  }

  public static byte[] encode(SemanticChunk chunk) throws IOException {
    // Build 24 section stream
    ByteArrayOutputStream dataOut = new ByteArrayOutputStream();
    byte[][] sky = new byte[SemanticChunk.MODERN_SECTION_COUNT + 2][];
    byte[][] block = new byte[SemanticChunk.MODERN_SECTION_COUNT + 2][];
    try (DataOutputStream out = new DataOutputStream(dataOut)) {
      for (int i = 0; i < SemanticChunk.MODERN_SECTION_COUNT; i++) {
        int sectionY = SemanticChunk.MODERN_MIN_SECTION + i;
        SemanticChunkSection section = find(chunk, sectionY);
        writeSection(out, section);
        int lightIndex = i + 1; // +1 for bottom neighbor in light mask convention
        sky[lightIndex] = section.skyLight().orElse(null);
        block[lightIndex] = section.blockLight().orElse(null);
      }
    }
    ByteArrayOutputStream packet = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(packet)) {
      out.writeInt(chunk.chunkX());
      out.writeInt(chunk.chunkZ());
      writeEmptyHeightmaps(out);
      byte[] data = dataOut.toByteArray();
      MinecraftOutput.varInt(out, data.length);
      out.write(data);
      MinecraftOutput.varInt(out, 0); // no block entities
      writeLight(out, sky, block);
    }
    return packet.toByteArray();
  }

  private static SemanticChunkSection find(SemanticChunk chunk, int sectionY) {
    for (SemanticChunk.SectionSlot slot : chunk.sections()) {
      if (slot.sectionY() == sectionY) return slot.section();
    }
    return SemanticChunkSection.air();
  }

  private static void writeSection(DataOutputStream out, SemanticChunkSection section) throws IOException {
    out.writeShort(section.nonAirCount());
    writePaletted(out, section.blockStates(), 4096, 8, 15);
    int[] biomes = section.biomes().length == 64 ? section.biomes() : new int[64];
    writePaletted(out, biomes, 64, 3, 6);
  }

  private static void writePaletted(DataOutputStream out, int[] values, int entries, int maxIndirectBits, int directBits)
      throws IOException {
    java.util.LinkedHashMap<Integer, Integer> index = new java.util.LinkedHashMap<>();
    for (int v : values) index.putIfAbsent(v, index.size());
    int unique = index.size();
    if (unique == 1) {
      out.writeByte(0);
      MinecraftOutput.varInt(out, values[0]);
      MinecraftOutput.varInt(out, 0);
      return;
    }
    if (unique <= (1 << maxIndirectBits)) {
      int bits = Math.max(entries == 64 ? 1 : 4, 32 - Integer.numberOfLeadingZeros(unique - 1));
      out.writeByte(bits);
      MinecraftOutput.varInt(out, unique);
      int[] palette = new int[unique];
      for (var e : index.entrySet()) palette[e.getValue()] = e.getKey();
      for (int id : palette) MinecraftOutput.varInt(out, id);
      int[] indices = new int[entries];
      for (int i = 0; i < entries; i++) indices[i] = index.get(values[i]);
      BlockStateMaps.writeVarIntLongArray(out, BlockStateMaps.pack(indices, bits, true));
    } else {
      out.writeByte(directBits);
      BlockStateMaps.writeVarIntLongArray(out, BlockStateMaps.pack(values, directBits, true));
    }
  }

  private static void writeEmptyHeightmaps(DataOutputStream out) throws IOException {
    // Anonymous root compound (1.20.2+ network NBT): type + end, no root name.
    out.writeByte(10);
    out.writeByte(0);
  }

  private static void skipChunkBlockEntity(DataInputStream in) throws IOException {
    in.readByte(); // packed xz
    in.readShort(); // y
    MinecraftInput.varInt(in); // type
    NetworkNbt.skip(in);
  }

  private static LightData readLight(DataInputStream in) throws IOException {
    long[] skyMask = readBitSet(in);
    long[] blockMask = readBitSet(in);
    long[] emptySky = readBitSet(in);
    long[] emptyBlock = readBitSet(in);
    int skyCount = MinecraftInput.varInt(in);
    if (skyCount < 0 || skyCount > 64) throw new IOException("sky light arrays " + skyCount);
    List<byte[]> sky = new ArrayList<>(skyCount);
    for (int i = 0; i < skyCount; i++) sky.add(readLightArray(in));
    int blockCount = MinecraftInput.varInt(in);
    if (blockCount < 0 || blockCount > 64) throw new IOException("block light arrays " + blockCount);
    List<byte[]> block = new ArrayList<>(blockCount);
    for (int i = 0; i < blockCount; i++) block.add(readLightArray(in));
    return new LightData(skyMask, blockMask, emptySky, emptyBlock, sky, block);
  }

  private static void writeLight(DataOutputStream out, byte[][] sky, byte[][] block) throws IOException {
    // Minimal: empty masks / no arrays — client computes or uses section defaults.
    MinecraftOutput.varInt(out, 0);
    MinecraftOutput.varInt(out, 0);
    MinecraftOutput.varInt(out, 0);
    MinecraftOutput.varInt(out, 0);
    MinecraftOutput.varInt(out, 0);
    MinecraftOutput.varInt(out, 0);
  }

  private static long[] readBitSet(DataInputStream in) throws IOException {
    return BlockStateMaps.readVarIntLongArray(in, 8);
  }

  private static byte[] readLightArray(DataInputStream in) throws IOException {
    int len = MinecraftInput.varInt(in);
    if (len != 2048) throw new IOException("light array len " + len);
    byte[] data = new byte[2048];
    in.readFully(data);
    return data;
  }

  private record LightData(long[] skyMask, long[] blockMask, long[] emptySky, long[] emptyBlock,
                           List<byte[]> sky, List<byte[]> block) {
    byte[] skyLightForIndex(int sectionIndex) {
      return pick(skyMask, emptySky, sky, sectionIndex + 1);
    }
    byte[] blockLightForIndex(int sectionIndex) {
      return pick(blockMask, emptyBlock, block, sectionIndex + 1);
    }
    private static byte[] pick(long[] mask, long[] empty, List<byte[]> arrays, int bit) {
      if (isSet(empty, bit)) return new byte[2048];
      if (!isSet(mask, bit)) return BlockStateMaps.emptyLight();
      int ordinal = Long.bitCount(maskBitsBelow(mask, bit));
      // recount how many set bits below bit across mask array
      int count = 0;
      for (int b = 0; b < bit; b++) if (isSet(mask, b)) count++;
      if (count < 0 || count >= arrays.size()) return BlockStateMaps.emptyLight();
      return arrays.get(count);
    }
    private static boolean isSet(long[] mask, int bit) {
      int longIndex = bit >>> 6;
      if (longIndex >= mask.length) return false;
      return ((mask[longIndex] >>> (bit & 63)) & 1L) != 0;
    }
    private static long maskBitsBelow(long[] mask, int bit) { return 0; }
  }
}
