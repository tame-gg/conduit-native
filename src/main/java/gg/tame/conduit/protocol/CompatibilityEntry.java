// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol;

/**
 * Machine-readable answer to: can client A connect to backend B through Conduit?
 */
public record CompatibilityEntry(
    int clientProtocol,
    int backendProtocol,
    TranslationSupport support,
    CompatibilityCompleteness completeness,
    ValidationStatus validation,
    String notes
) {
  public CompatibilityEntry {
    if (notes == null) notes = "";
    if (validation == null) validation = ValidationStatus.CODEC_PARTIAL;
  }

  /** Back-compatible constructor for paths with no explicit validation record. */
  public CompatibilityEntry(int clientProtocol, int backendProtocol, TranslationSupport support,
                            CompatibilityCompleteness completeness, String notes) {
    this(clientProtocol, backendProtocol, support, completeness,
        support == TranslationSupport.DIRECT ? ValidationStatus.CODEC_COMPLETE
            : support == TranslationSupport.UNSUPPORTED ? ValidationStatus.CODEC_PARTIAL
            : ValidationStatus.TRANSLATED_PARTIAL,
        notes);
  }

  public boolean selectable() {
    return support == TranslationSupport.DIRECT
        || (support == TranslationSupport.TRANSLATED && completeness != CompatibilityCompleteness.NONE);
  }
}
