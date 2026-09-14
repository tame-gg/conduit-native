package gg.tame.conduit.protocol;

import gg.tame.conduit.protocol.translate.Protocol765To766Translator;

/** Selects a translator only for implemented pairings. */
public final class Translators {
  private Translators() {}
  public static ProtocolTranslator forPair(int clientProtocol, int backendProtocol) {
    TranslationSupport support = ProtocolCompatibility.between(clientProtocol, backendProtocol);
    if (support == TranslationSupport.DIRECT) return IdentityTranslator.INSTANCE;
    if (support == TranslationSupport.TRANSLATED) {
      if (clientProtocol == 765 && backendProtocol == 766) return Protocol765To766Translator.V765_TO_766;
      if (clientProtocol == 766 && backendProtocol == 765) return Protocol765To766Translator.V766_TO_765;
    }
    throw new IllegalArgumentException("no packet translator for " + clientProtocol + " → " + backendProtocol + " (" + support + ")");
  }
}
