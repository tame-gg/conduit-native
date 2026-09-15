package gg.tame.conduit.protocol.chunk;

import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 1.13 (393) Chunk Data codec.
 * Wire: x,z, groundUp, primaryBitMask, data(size+bytes), blockEntities(nbt[]).
 * Section (1.13): bitsPerBlock, optional palette, data longs (tight), blockLight[2048], skyLight[2048].
 * Full chunk appends 1024 biome ints after sections.
 * Public layout: PrismarineJS minecraft-data 1.13 + wiki.vg Chunk Format (1.13 era).
 */
public final class ChunkCodec393 {
  private static final int MAX_DATA = 2 * 1024 * 1024;
  private static final int MAX_BLOCK_ENTITIES = 4096;

  private ChunkCodec393() {}

  public static SemanticChunk decode(byte[] body) throws IOException {
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
      int x = in.readInt();
      int z = in.readInt();
      boolean groundUp = in.readBoolean();
      int bitMap = MinecraftInput.varInt(in);
      if (bitMap < 0 || bitMap > 0xFFFF) throw new IOException("invalid section bitmask");
      int dataSize = MinecraftInput.varInt(in);
      if (dataSize < 0 || dataSize > MAX_DATA) throw new IOException("chunk data size " + dataSize);
      byte[] data = new byte[dataSize];
      in.readFully(data);
      int beCount = MinecraftInput.varInt(in);
      if (beCount < 0 || beCount > MAX_BLOCK_ENTITIES) throw new IOException("block entities " + beCount);
      List<byte[]> entities = new ArrayList<>();
      for (int i = 0; i < beCount; i++) {
        // Discard block-entity payloads; foundation rematerializes chunks without them.
        gg.tame.conduit.protocol.NetworkNbt.skip(in);
      }
      return decodeData(x, z, groundUp, bitMap, data, entities);
    }
  }

  private static SemanticChunk decodeData(int x, int z, boolean groundUp, int bitMap, byte[] data, List<byte[]> entities)
      throws IOException {
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
      List<SemanticChunk.SectionSlot> sections = new ArrayList<>();
      for (int sectionY = 0; sectionY < 16; sectionY++) {
        if ((bitMap & (1 << sectionY)) == 0) continue;
        sections.add(new SemanticChunk.SectionSlot(sectionY, readSection(in, true)));
      }
      int[] biomes = new int[0];
      if (groundUp) {
        biomes = new int[1024];
        for (int i = 0; i < 1024; i++) biomes[i] = in.readInt();
      }
      return new SemanticChunk(x, z, groundUp, sections, biomes, entities, new byte[0]);
    }
  }

  public static byte[] encode(SemanticChunk chunk) throws IOException {
    return encodeLegacy(chunk.toLegacy113());
  }

  /** Encode assuming chunk is already in legacy Y range with 393 state IDs. */
  public static byte[] encodeLegacy(SemanticChunk legacy) throws IOException {
    int bitMap = 0;
    ByteArrayOutputStream sectionBytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(sectionBytes)) {
      SemanticChunk.SectionSlot[] ordered = new SemanticChunk.SectionSlot[16];
      for (SemanticChunk.SectionSlot slot : legacy.sections()) {
        if (slot.sectionY() >= 0 && slot.sectionY() <= 15) ordered[slot.sectionY()] = slot;
      }
      for (int y = 0; y < 16; y++) {
        if (ordered[y] == null) continue;
        bitMap |= (1 << y);
        writeSection(out, ordered[y].section(), true);
      }
      if (legacy.fullChunk()) {
        int[] biomes = legacy.biomes1024().length == 1024 ? legacy.biomes1024() : plainsBiomes();
        for (int biome : biomes) out.writeInt(biome);
      }
    }
    byte[] data = sectionBytes.toByteArray();
    ByteArrayOutputStream packet = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(packet)) {
      out.writeInt(legacy.chunkX());
      out.writeInt(legacy.chunkZ());
      out.writeBoolean(true);
      MinecraftOutput.varInt(out, bitMap);
      MinecraftOutput.varInt(out, data.length);
      out.write(data);
      MinecraftOutput.varInt(out, 0);
    }
    return packet.toByteArray();
  }

  private static SemanticChunkSection readSection(DataInputStream in, boolean withLight) throws IOException {
    int bits = in.readUnsignedByte();
    if (bits > 32) throw new IOException("bitsPerBlock " + bits);
    int[] palette = null;
    if (bits <= 8) {
      int paletteLen = MinecraftInput.varInt(in);
      if (paletteLen < 0 || paletteLen > 4096) throw new IOException("palette " + paletteLen);
      palette = new int[paletteLen];
      for (int i = 0; i < paletteLen; i++) palette[i] = MinecraftInput.varInt(in);
      if (bits == 0) bits = 4; // notchian quirk handling — treat empty as 4 when needed
    }
    if (bits < 4 && palette != null) bits = 4;
    long[] data = BlockStateMaps.readVarIntLongArray(in, 4096);
    int[] indices = BlockStateMaps.unpack(data, Math.max(bits, 1), 4096, false);
    int[] states = new int[4096];
    int nonAir = 0;
    for (int i = 0; i < 4096; i++) {
      int idx = indices[i];
      int state = palette == null ? idx : (idx < palette.length ? palette[idx] : 0);
      states[i] = state;
      if (state != 0) nonAir++;
    }
    byte[] blockLight = new byte[0];
    byte[] skyLight = new byte[0];
    if (withLight) {
      blockLight = new byte[2048];
      in.readFully(blockLight);
      skyLight = new byte[2048];
      in.readFully(skyLight);
    }
    return new SemanticChunkSection(nonAir, states, new int[0], blockLight, skyLight);
  }

  private static void writeSection(DataOutputStream out, SemanticChunkSection section, boolean withLight) throws IOException {
    // Build compact palette
    java.util.LinkedHashMap<Integer, Integer> index = new java.util.LinkedHashMap<>();
    int[] states = section.blockStates();
    for (int state : states) index.putIfAbsent(state, index.size());
    int unique = index.size();
    int bits;
    if (unique <= 1) bits = 0;
    else if (unique <= 16) bits = Math.max(4, ceilLog2(unique));
    else if (unique <= 256) bits = ceilLog2(unique);
    else bits = 14; // 1.13 global palette bits
    if (bits > 8) {
      // direct / global
      out.writeByte(bits);
      int[] values = states.clone();
      long[] packed = BlockStateMaps.pack(values, bits, false);
      BlockStateMaps.writeVarIntLongArray(out, packed);
    } else {
      if (bits == 0) bits = 4; // send single-value as 4-bit palette of size 1
      out.writeByte(bits);
      MinecraftOutput.varInt(out, unique);
      int[] palette = new int[unique];
      for (var e : index.entrySet()) palette[e.getValue()] = e.getKey();
      for (int id : palette) MinecraftOutput.varInt(out, id);
      int[] indices = new int[4096];
      for (int i = 0; i < 4096; i++) indices[i] = index.get(states[i]);
      long[] packed = BlockStateMaps.pack(indices, bits, false);
      BlockStateMaps.writeVarIntLongArray(out, packed);
    }
    if (withLight) {
      byte[] block = section.blockLight().orElse(BlockStateMaps.emptyLight());
      byte[] sky = section.skyLight().orElse(BlockStateMaps.emptyLight());
      if (block.length != 2048) block = BlockStateMaps.emptyLight();
      if (sky.length != 2048) sky = BlockStateMaps.emptyLight();
      out.write(block);
      out.write(sky);
    }
  }

  private static int ceilLog2(int v) {
    return 32 - Integer.numberOfLeadingZeros(v - 1);
  }

  private static int[] plainsBiomes() {
    int[] biomes = new int[1024];
    java.util.Arrays.fill(biomes, BlockStateMaps.plainsBiome113());
    return biomes;
  }
}
