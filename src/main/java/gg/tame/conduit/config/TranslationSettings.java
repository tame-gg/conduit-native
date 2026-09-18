// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.config;

/**
 * Cross-version translation engine selection.
 *
 * <ul>
 *   <li>{@link TranslationEngine#VIA_PREFERRED} — ViaVersion ecosystem first, native fallback</li>
 *   <li>{@link TranslationEngine#VIA} — Via only (native pairs unused when Via cannot translate)</li>
 *   <li>{@link TranslationEngine#NATIVE} — Conduit native translators only</li>
 * </ul>
 */
public record TranslationSettings(
    boolean enabled,
    TranslationEngine engine,
    boolean loadViaBackwards,
    boolean loadViaRewind,
    boolean loadViaLegacy,
    String dataFolder
) {
  public TranslationSettings {
    if (engine == null) engine = TranslationEngine.VIA_PREFERRED;
    if (dataFolder == null || dataFolder.isBlank()) dataFolder = "via";
  }

  public static TranslationSettings defaults() {
    // On by default: without it a cross-version player loses every packet the native
    // pair drops -- sounds, particles, scoreboards, titles and boss bars among them --
    // and Via carries all of those today. VIA_PREFERRED still falls back to the native
    // translators for any pair Via has no path for. ViaLegacy stays off by default: it
    // pulls extra transitive deps and is only required for backends ≤1.7.10.
    return new TranslationSettings(true, TranslationEngine.VIA_PREFERRED, true, true, false, "via");
  }

  public enum TranslationEngine {
    VIA_PREFERRED,
    VIA,
    NATIVE;

    public static TranslationEngine parse(String raw) {
      if (raw == null || raw.isBlank()) return VIA_PREFERRED;
      return switch (raw.strip().toLowerCase(java.util.Locale.ROOT).replace('_', '-')) {
        case "via-preferred", "viapreferred", "preferred" -> VIA_PREFERRED;
        case "via", "viaversion" -> VIA;
        case "native", "conduit" -> NATIVE;
        default -> throw new IllegalArgumentException("translation.engine must be via-preferred, via or native");
      };
    }
  }
}
