package gg.tame.conduit.protocol;

/** Client and backend protocol pairing. TRANSLATED is never claimed without a real translator. */
public final class ProtocolCompatibility {
  private ProtocolCompatibility() {}
  public static boolean implemented(int protocol) {
    return protocol == ProtocolVersion.MINECRAFT_1_20_1.number() || protocol == ProtocolVersion.MINECRAFT_1_20_4.number();
  }
  public static TranslationSupport between(int clientProtocol, int backendProtocol) {
    if (!implemented(clientProtocol) || !implemented(backendProtocol)) return TranslationSupport.UNSUPPORTED;
    if (clientProtocol == backendProtocol) return TranslationSupport.DIRECT;
    return TranslationSupport.UNSUPPORTED;
  }
}
