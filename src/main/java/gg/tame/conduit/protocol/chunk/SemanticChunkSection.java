// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol.chunk;

import java.util.Arrays;
import java.util.Optional;

/** One 16×16×16 section in semantic form (global block-state IDs for a specific protocol era). */
public final class SemanticChunkSection {
  private final int nonAirCount;
  private final int[] blockStates; // 4096 global state IDs
  private final int[] biomes; // 64 biome IDs (modern) or empty
  private final byte[] blockLight; // 2048 or empty
  private final byte[] skyLight; // 2048 or empty

  public SemanticChunkSection(int nonAirCount, int[] blockStates, int[] biomes, byte[] blockLight, byte[] skyLight) {
    if (blockStates == null || blockStates.length != 4096) throw new IllegalArgumentException("blockStates must be 4096");
    this.nonAirCount = Math.max(0, nonAirCount);
    this.blockStates = blockStates.clone();
    this.biomes = biomes == null ? new int[0] : biomes.clone();
    this.blockLight = blockLight == null ? new byte[0] : blockLight.clone();
    this.skyLight = skyLight == null ? new byte[0] : skyLight.clone();
  }

  public static SemanticChunkSection air() {
    return new SemanticChunkSection(0, new int[4096], new int[64], new byte[0], new byte[0]);
  }

  public int nonAirCount() { return nonAirCount; }
  public int[] blockStates() { return blockStates.clone(); }
  public int[] biomes() { return biomes.clone(); }
  public Optional<byte[]> blockLight() { return blockLight.length == 0 ? Optional.empty() : Optional.of(blockLight.clone()); }
  public Optional<byte[]> skyLight() { return skyLight.length == 0 ? Optional.empty() : Optional.of(skyLight.clone()); }

  public boolean isEmpty() {
    if (nonAirCount == 0) {
      for (int state : blockStates) if (state != 0) return false;
      return true;
    }
    return false;
  }

  public SemanticChunkSection remapStates(java.util.function.IntUnaryOperator mapper) {
    int[] next = new int[4096];
    int nonAir = 0;
    for (int i = 0; i < 4096; i++) {
      int mapped = mapper.applyAsInt(blockStates[i]);
      next[i] = mapped;
      if (mapped != 0) nonAir++;
    }
    return new SemanticChunkSection(nonAir, next, biomes, blockLight, skyLight);
  }
}
