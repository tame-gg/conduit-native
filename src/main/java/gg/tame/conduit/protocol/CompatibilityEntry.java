package gg.tame.conduit.protocol;

/**
 * Machine-readable answer to: can client A connect to backend B through Conduit?
 */
public record CompatibilityEntry(
    int clientProtocol,
    int backendProtocol,
    TranslationSupport support,
    CompatibilityCompleteness completeness,
    String notes
) {
  public CompatibilityEntry {
    if (notes == null) notes = "";
  }

  public boolean selectable() {
    return support == TranslationSupport.DIRECT
        || (support == TranslationSupport.TRANSLATED && completeness != CompatibilityCompleteness.NONE);
  }
}
