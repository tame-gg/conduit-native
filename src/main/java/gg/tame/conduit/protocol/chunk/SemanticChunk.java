package gg.tame.conduit.protocol.chunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Protocol-agnostic chunk column.
 * Vertical policy for 765→393: only sections with world Y in [0, 255] are retained
 * (modern section index 4..19 when minY=-64). Sections outside that range are dropped.
 */
public final class SemanticChunk {
  public static final int LEGACY_SECTIONS = 16; // Y 0..255
  public static final int MODERN_MIN_SECTION = -4; // Y -64
  public static final int MODERN_SECTION_COUNT = 24; // -4 .. 19

  private final int chunkX;
  private final int chunkZ;
  private final boolean fullChunk;
  private final List<SectionSlot> sections;
  private final int[] biomes1024; // 1.13 full-chunk biome array
  private final List<byte[]> blockEntityNbts;
  private final byte[] heightmapsNbt; // optional modern heightmaps (opaque NBT bytes without length)

  public record SectionSlot(int sectionY, SemanticChunkSection section) {}

  public SemanticChunk(int chunkX, int chunkZ, boolean fullChunk, List<SectionSlot> sections,
                       int[] biomes1024, List<byte[]> blockEntityNbts, byte[] heightmapsNbt) {
    this.chunkX = chunkX;
    this.chunkZ = chunkZ;
    this.fullChunk = fullChunk;
    this.sections = List.copyOf(sections);
    this.biomes1024 = biomes1024 == null ? new int[0] : biomes1024.clone();
    this.blockEntityNbts = blockEntityNbts == null ? List.of() : List.copyOf(blockEntityNbts);
    this.heightmapsNbt = heightmapsNbt == null ? new byte[0] : heightmapsNbt.clone();
  }

  public int chunkX() { return chunkX; }
  public int chunkZ() { return chunkZ; }
  public boolean fullChunk() { return fullChunk; }
  public List<SectionSlot> sections() { return sections; }
  public int[] biomes1024() { return biomes1024.clone(); }
  public List<byte[]> blockEntityNbts() { return blockEntityNbts; }
  public byte[] heightmapsNbt() { return heightmapsNbt.clone(); }

  /** Keep only legacy-representable sections (Y 0..255). Does not remap block-state IDs. */
  public SemanticChunk projectLegacyHeight() {
    List<SectionSlot> legacy = new ArrayList<>();
    for (SectionSlot slot : sections) {
      if (slot.sectionY() < 0 || slot.sectionY() > 15) continue;
      legacy.add(slot);
    }
    int[] biomes = new int[1024];
    java.util.Arrays.fill(biomes, BlockStateMaps.plainsBiome113());
    if (biomes1024.length == 1024) System.arraycopy(biomes1024, 0, biomes, 0, 1024);
    return new SemanticChunk(chunkX, chunkZ, true, legacy, biomes, List.of(), new byte[0]);
  }

  public SemanticChunk remapBlockStates(java.util.function.IntUnaryOperator mapper) {
    List<SectionSlot> next = new ArrayList<>(sections.size());
    for (SectionSlot slot : sections) {
      next.add(new SectionSlot(slot.sectionY(), slot.section().remapStates(mapper)));
    }
    return new SemanticChunk(chunkX, chunkZ, fullChunk, next, biomes1024, blockEntityNbts, heightmapsNbt);
  }

  /** 765→393: drop out-of-range sections and remap block states. */
  public SemanticChunk toLegacy113() {
    return projectLegacyHeight().remapBlockStates(BlockStateMaps::to393);
  }

  public SemanticChunk toModern765() {
    List<SectionSlot> modern = new ArrayList<>();
    for (SectionSlot slot : sections) {
      if (slot.sectionY() < 0 || slot.sectionY() > 15) continue;
      modern.add(new SectionSlot(slot.sectionY(), slot.section().remapStates(BlockStateMaps::to765)));
    }
    return new SemanticChunk(chunkX, chunkZ, true, modern, new int[0], List.of(), new byte[0]);
  }
}
