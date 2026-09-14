package gg.tame.conduit.config;

import gg.tame.conduit.modded.UnknownModdedPolicy;

/** Phase 3 modded / Forge / NeoForge settings. */
public record ModdedSettings(
    boolean enabled,
    int knownPacksLimit,
    boolean handshakeCacheEnabled,
    int handshakeCacheCapacity,
    long handshakeCacheTtlMs,
    boolean forgeCompat,
    boolean neoForgeCompat,
    boolean fabricCompat,
    UnknownModdedPolicy unknownPolicy,
    boolean packetQueueEnabled,
    int packetQueueMaxDepth,
    boolean logModHandshakes
) {
  public ModdedSettings {
    if (knownPacksLimit < 1 || knownPacksLimit > 16_384) {
      throw new IllegalArgumentException("modded.known-packs-limit must be 1..16384");
    }
    if (handshakeCacheCapacity < 16 || handshakeCacheCapacity > 65_536) {
      throw new IllegalArgumentException("modded.handshake-cache-capacity must be 16..65536");
    }
    if (handshakeCacheTtlMs < 1_000L || handshakeCacheTtlMs > 3_600_000L) {
      throw new IllegalArgumentException("modded.handshake-cache-ttl-ms must be 1000..3600000");
    }
    if (packetQueueMaxDepth < 1 || packetQueueMaxDepth > 16_384) {
      throw new IllegalArgumentException("modded.packet-queue-max-depth must be 1..16384");
    }
    if (unknownPolicy == null) unknownPolicy = UnknownModdedPolicy.ALLOW;
  }

  public static ModdedSettings defaults() {
    return new ModdedSettings(
        true,
        1024,
        true,
        4096,
        300_000L,
        true,
        true,
        true,
        UnknownModdedPolicy.ALLOW,
        true,
        512,
        false);
  }
}
