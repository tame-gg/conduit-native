package gg.tame.conduit.protocol;

/** Same-version vs translated vs unsupported client/backend pairing. */
public final class ProtocolCompatibility {
  private ProtocolCompatibility() {}
  public static boolean implemented(int protocol) { return ProtocolDefinition.hasCodec(protocol); }
  public static TranslationSupport between(int clientProtocol, int backendProtocol) {
    if (!implemented(clientProtocol) || !implemented(backendProtocol)) return TranslationSupport.UNSUPPORTED;
    if (clientProtocol == backendProtocol) return TranslationSupport.DIRECT;
    return TranslationSupport.UNSUPPORTED;
  }
}
