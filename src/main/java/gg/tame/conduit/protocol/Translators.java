package gg.tame.conduit.protocol;

/**
 * Selects a translator for an ordered (client, backend) protocol pair.
 *
 * <p>Both protocol numbers are inputs. The selection is never made from the
 * backend version alone, and never mirrored: 765&rarr;393 is a separate
 * registration from 393&rarr;765 and may not exist when its opposite does.
 */
public final class Translators {
  private Translators() {}

  public static ProtocolTranslator forPair(int clientProtocol, int backendProtocol) {
    TranslationSupport support = ProtocolCompatibility.between(clientProtocol, backendProtocol);
    if (support == TranslationSupport.DIRECT) return IdentityTranslator.INSTANCE;
    return TranslatorRegistry.find(clientProtocol, backendProtocol)
        .orElseThrow(() -> new IllegalArgumentException(
            "no packet translator for " + clientProtocol + " → " + backendProtocol
                + " (" + support + ")"));
  }
}
