package gg.tame.conduit.protocol;

/** Same-version vs translated vs unsupported client/backend pairing. */
public final class ProtocolCompatibility {
  private ProtocolCompatibility() {}
  public static boolean implemented(int protocol) { return ProtocolDefinition.hasCodec(protocol); }
  public static TranslationSupport between(int clientProtocol, int backendProtocol) {
    if (!implemented(clientProtocol) || !implemented(backendProtocol)) return TranslationSupport.UNSUPPORTED;
    if (clientProtocol == backendProtocol) return TranslationSupport.DIRECT;
    if (is765_766(clientProtocol, backendProtocol)) return TranslationSupport.TRANSLATED;
    if (is393_765(clientProtocol, backendProtocol)) return TranslationSupport.TRANSLATED;
    // 765↔776 remains intentionally unsupported until a real translator exists.
    return TranslationSupport.UNSUPPORTED;
  }

  private static boolean is765_766(int a, int b) {
    return (a == 765 && b == 766) || (a == 766 && b == 765);
  }

  private static boolean is393_765(int a, int b) {
    return (a == 393 && b == 765) || (a == 765 && b == 393);
  }
}
