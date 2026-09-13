package gg.tame.conduit.protocol;

/** Selects a translator only for implemented pairings. Cross-version translation is not implemented. */
public final class Translators {
  private Translators() {}
  public static ProtocolTranslator forPair(int clientProtocol, int backendProtocol) {
    TranslationSupport support = ProtocolCompatibility.between(clientProtocol, backendProtocol);
    if (support == TranslationSupport.DIRECT) return IdentityTranslator.INSTANCE;
    throw new IllegalArgumentException("no packet translator for " + clientProtocol + " → " + backendProtocol + " (" + support + ")");
  }
}
