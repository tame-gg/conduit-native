package gg.tame.conduit.protocol;

/**
 * Same-version vs translated vs unsupported client/backend pairing.
 *
 * <p>The decision needs BOTH protocol numbers. A backend's version alone never
 * determines the path: the same 1.20.4 backend is DIRECT for a 1.20.4 client,
 * TRANSLATED for a 1.13 client, and UNSUPPORTED for a client Conduit has no
 * translator for.
 */
public final class ProtocolCompatibility {
  private ProtocolCompatibility() {}

  /** Whether Conduit has any packet table for this protocol. */
  public static boolean implemented(int protocol) { return ProtocolDefinition.hasCodec(protocol); }

  public static TranslationSupport between(int clientProtocol, int backendProtocol) {
    // Both sides need a codec before either path is possible.
    if (!implemented(clientProtocol) || !implemented(backendProtocol)) return TranslationSupport.UNSUPPORTED;
    if (clientProtocol == backendProtocol) return TranslationSupport.DIRECT;
    // Ordered lookup: a 393->765 translator does not imply 765->393.
    return TranslatorRegistry.has(clientProtocol, backendProtocol)
        ? TranslationSupport.TRANSLATED
        : TranslationSupport.UNSUPPORTED;
  }
}
