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
 * 1.14 (477) Chunk Data codec.
 *
 * <pre>
 *   x:i32  z:i32  groundUp:bool  bitMap:VarInt  heightmaps:NBT
 *   chunkData:PrefixedBytes  blockEntities:PrefixedNamedNbt[]
 * </pre>
 *
 * <p>Section bodies match 1.13 (palette + longs + optional biomes) but
 * <em>without</em> the per-section light arrays — those moved to Update Light.
 * Heightmaps may be an empty compound; the client initialises missing maps.
 */
public final class ChunkCodec477 {
  private static final int MAX_DATA = 2 * 1024 * 1024;
  private static final int MAX_BLOCK_ENTITIES = 4096;

  private ChunkCodec477() {}

  public static SemanticChunk decode(byte[] body) throws IOException {
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
      int x = in.readInt();
      int z = in.readInt();
      boolean groundUp = in.readBoolean();
      int bitMap = MinecraftInput.varInt(in);
      if (bitMap < 0 || bitMap > 0xFFFF) throw new IOException("invalid section bitmask");
      ByteArrayOutputStream heightmaps = new ByteArrayOutputStream();
      // 1.14 still uses named (disk-style) NBT on the wire for heightmaps and
      // block entities — the nameless network form arrives later.
      NetworkNbt.copyNamed(in, new DataOutputStream(heightmaps));
      int dataSize = MinecraftInput.varInt(in);
      if (dataSize < 0 || dataSize > MAX_DATA) throw new IOException("chunk data size " + dataSize);
      byte[] data = new byte[dataSize];
      in.readFully(data);
      int beCount = MinecraftInput.varInt(in);
      if (beCount < 0 || beCount > MAX_BLOCK_ENTITIES) throw new IOException("block entities " + beCount);
      List<byte[]> entities = new ArrayList<>();
      for (int i = 0; i < beCount; i++) NetworkNbt.skipNamed(in);
      return decodeData(x, z, groundUp, bitMap, data, entities, heightmaps.toByteArray());
    }
  }

  private static SemanticChunk decodeData(int x, int z, boolean groundUp, int bitMap, byte[] data,
                                          List<byte[]> entities, byte[] heightmaps) throws IOException {
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
      List<SemanticChunk.SectionSlot> sections = new ArrayList<>();
      for (int sectionY = 0; sectionY < 16; sectionY++) {
        if ((bitMap & (1 << sectionY)) == 0) continue;
        sections.add(new SemanticChunk.SectionSlot(sectionY, readSection(in)));
      }
      int[] biomes = new int[0];
      if (groundUp) {
        biomes = new int[256];
        for (int index = 0; index < 256; index++) biomes[index] = in.readInt();
      }
      return new SemanticChunk(x, z, groundUp, sections, biomes, entities, heightmaps);
    }
  }

  /** Encode a semantic chunk for 1.14 wire (empty heightmaps compound, no section light). */
  public static byte[] encode(SemanticChunk chunk) throws IOException {
    int bitMap = 0;
    ByteArrayOutputStream sectionBytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(sectionBytes)) {
      SemanticChunk.SectionSlot[] ordered = new SemanticChunk.SectionSlot[16];
      for (SemanticChunk.SectionSlot slot : chunk.sections()) {
        if (slot.sectionY() >= 0 && slot.sectionY() <= 15) ordered[slot.sectionY()] = slot;
      }
      for (int y = 0; y < 16; y++) {
        if (ordered[y] == null) continue;
        bitMap |= (1 << y);
        writeSection(out, ordered[y].section());
      }
      if (chunk.fullChunk()) {
        int[] source = chunk.biomes1024();
        for (int index = 0; index < 256; index++) {
          out.writeInt(index < source.length ? source[index] : BlockStateMaps.plainsBiome113());
        }
      }
    }
    byte[] data = sectionBytes.toByteArray();
    ByteArrayOutputStream packet = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(packet)) {
      out.writeInt(chunk.chunkX());
      out.writeInt(chunk.chunkZ());
      out.writeBoolean(true);
      MinecraftOutput.varInt(out, bitMap);
      writeEmptyHeightmaps(out);
      MinecraftOutput.varInt(out, data.length);
      out.write(data);
      MinecraftOutput.varInt(out, 0);
    }
    return packet.toByteArray();
  }

  /**
   * Builds an Update Light body that paints every non-empty section full-bright
   * from the light arrays already stored on the semantic chunk (or empty light
   * when the source had none). Used when synthesising 477 light after a 404 chunk.
   */
  public static byte[] encodeUpdateLight(SemanticChunk chunk) throws IOException {
    int skyMask = 0;
    int blockMask = 0;
    ByteArrayOutputStream skyArrays = new ByteArrayOutputStream();
    ByteArrayOutputStream blockArrays = new ByteArrayOutputStream();
    DataOutputStream skyOut = new DataOutputStream(skyArrays);
    DataOutputStream blockOut = new DataOutputStream(blockArrays);
    for (int y = 0; y < 16; y++) {
      SemanticChunkSection section = null;
      for (SemanticChunk.SectionSlot slot : chunk.sections()) {
        if (slot.sectionY() == y) { section = slot.section(); break; }
      }
      if (section == null) continue;
      // Mask bit 0 is the section below Y=0; sections 0..15 occupy bits 1..16.
      int bit = 1 << (y + 1);
      byte[] sky = section.skyLight().orElse(null);
      byte[] block = section.blockLight().orElse(null);
      if (sky != null && sky.length == 2048) {
        skyMask |= bit;
        MinecraftOutput.varInt(skyOut, 2048);
        skyOut.write(sky);
      }
      if (block != null && block.length == 2048) {
        blockMask |= bit;
        MinecraftOutput.varInt(blockOut, 2048);
        blockOut.write(block);
      }
    }
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(body)) {
      MinecraftOutput.varInt(out, chunk.chunkX());
      MinecraftOutput.varInt(out, chunk.chunkZ());
      MinecraftOutput.varInt(out, skyMask);
      MinecraftOutput.varInt(out, blockMask);
      MinecraftOutput.varInt(out, 0); // empty sky mask
      MinecraftOutput.varInt(out, 0); // empty block mask
      out.write(skyArrays.toByteArray());
      out.write(blockArrays.toByteArray());
    }
    return body.toByteArray();
  }

  private static void writeEmptyHeightmaps(DataOutputStream out) throws IOException {
    // Named empty TAG_COMPOUND (empty name) — the 1.14 chunk wire form.
    out.writeByte(10);
    out.writeShort(0);
    out.writeByte(0);
  }

  private static SemanticChunkSection readSection(DataInputStream in) throws IOException {
    int bits = in.readUnsignedByte();
    if (bits > 32) throw new IOException("bitsPerBlock " + bits);
    int[] palette = null;
    if (bits <= 8) {
      int paletteLen = MinecraftInput.varInt(in);
      if (paletteLen < 0 || paletteLen > 4096) throw new IOException("palette " + paletteLen);
      palette = new int[paletteLen];
      for (int i = 0; i < paletteLen; i++) palette[i] = MinecraftInput.varInt(in);
      if (bits == 0) bits = 4;
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
    return new SemanticChunkSection(nonAir, states, new int[0], new byte[0], new byte[0]);
  }

  private static void writeSection(DataOutputStream out, SemanticChunkSection section) throws IOException {
    java.util.LinkedHashMap<Integer, Integer> index = new java.util.LinkedHashMap<>();
    int[] states = section.blockStates();
    for (int state : states) index.putIfAbsent(state, index.size());
    int unique = index.size();
    int bits;
    if (unique <= 1) bits = 0;
    else if (unique <= 16) bits = Math.max(4, ceilLog2(unique));
    else if (unique <= 256) bits = ceilLog2(unique);
    else bits = 14;
    if (bits > 8) {
      out.writeByte(bits);
      long[] packed = BlockStateMaps.pack(states.clone(), bits, false);
      BlockStateMaps.writeVarIntLongArray(out, packed);
    } else {
      if (bits == 0) bits = 4;
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
  }

  private static int ceilLog2(int v) {
    return 32 - Integer.numberOfLeadingZeros(v - 1);
  }
}
