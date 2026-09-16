package gg.tame.conduit.protocol;

import gg.tame.conduit.config.TranslationSettings;
import gg.tame.conduit.viaversion.ConduitViaBootstrap;
import gg.tame.conduit.viaversion.ConduitViaSupport;

/**
 * Same-version vs translated vs unsupported client/backend pairing.
 *
 * <p>The decision needs BOTH protocol numbers. A backend's version alone never
 * determines the path: the same 1.20.4 backend is DIRECT for a 1.20.4 client,
 * TRANSLATED for a 1.13 client, and UNSUPPORTED for a client Conduit has no
 * translator for.
 *
 * <p>When the ViaVersion ecosystem is loaded, its protocol graph is preferred
 * over Conduit's native translator registry (unless {@code translation.engine=native}).
 */
public final class ProtocolCompatibility {
  private ProtocolCompatibility() {}

  /** Whether Conduit has any packet table for this protocol. */
  public static boolean implemented(int protocol) { return ProtocolDefinition.hasCodec(protocol); }

  public static TranslationSupport between(int clientProtocol, int backendProtocol) {
    if (clientProtocol == backendProtocol) {
      if (implemented(clientProtocol) || ConduitViaSupport.knowsProtocol(clientProtocol)) {
        return TranslationSupport.DIRECT;
      }
      return TranslationSupport.UNSUPPORTED;
    }

    TranslationSettings settings = ConduitViaBootstrap.settings();
    TranslationSettings.TranslationEngine engine = settings.engine();

    boolean viaPossible = settings.enabled()
        && engine != TranslationSettings.TranslationEngine.NATIVE
        && ConduitViaSupport.supportsTranslation(clientProtocol, backendProtocol);
    boolean nativePossible = implemented(clientProtocol)
        && implemented(backendProtocol)
        && TranslatorRegistry.has(clientProtocol, backendProtocol);

    if (engine == TranslationSettings.TranslationEngine.VIA) {
      return viaPossible ? TranslationSupport.TRANSLATED : TranslationSupport.UNSUPPORTED;
    }
    if (engine == TranslationSettings.TranslationEngine.NATIVE) {
      return nativePossible ? TranslationSupport.TRANSLATED : TranslationSupport.UNSUPPORTED;
    }
    // via-preferred
    if (viaPossible || nativePossible) return TranslationSupport.TRANSLATED;
    return TranslationSupport.UNSUPPORTED;
  }
}
