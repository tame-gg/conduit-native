package gg.tame.conduit.modded;

import java.util.Locale;

/** Client/backend mod-environment classification. Evidence-based — never guess. */
public enum ModLoaderFamily {
  VANILLA,
  FABRIC,
  FORGE,
  NEOFORGE,
  UNKNOWN;

  public static ModLoaderFamily parse(String raw) {
    if (raw == null || raw.isBlank()) return UNKNOWN;
    return switch (raw.strip().toLowerCase(Locale.ROOT)) {
      case "vanilla" -> VANILLA;
      case "fabric", "quilt" -> FABRIC;
      case "forge", "legacy_forge", "legacy-forge", "fml", "fml1", "fml2", "fml3" -> FORGE;
      case "neoforge" -> NEOFORGE;
      case "unknown", "unknown_modded", "other" -> UNKNOWN;
      default -> throw new IllegalArgumentException("unknown mod loader: " + raw);
    };
  }

  public String displayName() {
    return switch (this) {
      case VANILLA -> "Vanilla";
      case FABRIC -> "Fabric";
      case FORGE -> "Forge";
      case NEOFORGE -> "NeoForge";
      case UNKNOWN -> "Unknown";
    };
  }
}
