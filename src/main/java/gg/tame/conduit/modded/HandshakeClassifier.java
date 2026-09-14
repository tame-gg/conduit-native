package gg.tame.conduit.modded;

/**
 * Accumulates protocol evidence into a mod-loader classification.
 * Does not demote a stronger signal (Forge/NeoForge/Fabric) back to vanilla.
 */
public final class HandshakeClassifier {
  private volatile ModLoaderFamily family = ModLoaderFamily.UNKNOWN;
  private volatile FmlAddressMarkers.MarkerKind marker = FmlAddressMarkers.MarkerKind.NONE;
  private volatile String cleanHost = "";

  public ModLoaderFamily family() { return family; }
  public FmlAddressMarkers.MarkerKind marker() { return marker; }
  public String cleanHost() { return cleanHost; }

  public void observeHandshakeHost(String requestedHost) {
    FmlAddressMarkers.ParsedHost parsed = FmlAddressMarkers.parse(requestedHost);
    this.cleanHost = parsed.cleanHost();
    this.marker = parsed.marker();
    if (parsed.hasMarker()) promote(parsed.family());
  }

  public void observeChannel(String channel) {
    ChannelDetector.ChannelClass kind = ChannelDetector.classify(channel);
    if (kind == ChannelDetector.ChannelClass.OTHER || kind == ChannelDetector.ChannelClass.INVALID) {
      if (kind == ChannelDetector.ChannelClass.OTHER && family == ModLoaderFamily.UNKNOWN) {
        // Unknown modded channel is evidence of modding, not of a specific loader.
        family = ModLoaderFamily.UNKNOWN;
      }
      return;
    }
    if (kind == ChannelDetector.ChannelClass.VANILLA) {
      if (family == ModLoaderFamily.UNKNOWN) family = ModLoaderFamily.VANILLA;
      return;
    }
    promote(ChannelDetector.familyHint(kind));
  }

  public void restore(ModLoaderFamily cached, FmlAddressMarkers.MarkerKind cachedMarker) {
    if (cached != null && cached != ModLoaderFamily.UNKNOWN) promote(cached);
    if (cachedMarker != null && cachedMarker != FmlAddressMarkers.MarkerKind.NONE && marker == FmlAddressMarkers.MarkerKind.NONE) {
      marker = cachedMarker;
    }
  }

  public void observeBrand(String brand) {
    if (brand == null || brand.isBlank()) return;
    String lower = brand.toLowerCase(java.util.Locale.ROOT);
    if (lower.contains("neoforge")) {
      promote(ModLoaderFamily.NEOFORGE);
    } else if (lower.contains("forge") && !lower.contains("neoforge")) {
      promote(ModLoaderFamily.FORGE);
    } else if (lower.contains("fabric") || lower.contains("quilt")) {
      promote(ModLoaderFamily.FABRIC);
    } else if (lower.contains("vanilla") && family == ModLoaderFamily.UNKNOWN) {
      family = ModLoaderFamily.VANILLA;
    }
  }

  /** Call after enough vanilla-only traffic with no modded evidence. */
  public void markLikelyVanilla() {
    if (family == ModLoaderFamily.UNKNOWN) family = ModLoaderFamily.VANILLA;
  }

  private void promote(ModLoaderFamily next) {
    if (next == null || next == ModLoaderFamily.UNKNOWN) return;
    if (family == ModLoaderFamily.UNKNOWN || family == ModLoaderFamily.VANILLA) {
      family = next;
      return;
    }
    if (family == ModLoaderFamily.FORGE && next == ModLoaderFamily.NEOFORGE) {
      family = ModLoaderFamily.NEOFORGE;
    }
  }
}
