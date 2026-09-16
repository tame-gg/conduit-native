package gg.tame.conduit.protocol;

import gg.tame.conduit.config.TranslationSettings;
import gg.tame.conduit.viaversion.ConduitViaBootstrap;
import gg.tame.conduit.viaversion.ConduitViaSupport;
import gg.tame.conduit.viaversion.ConduitViaTranslator;

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
    return forPair(clientProtocol, backendProtocol, "conduit", 25565);
  }

  public static ProtocolTranslator forPair(int clientProtocol, int backendProtocol, String host, int port) {
    TranslationSupport support = ProtocolCompatibility.between(clientProtocol, backendProtocol);
    if (support == TranslationSupport.DIRECT) return IdentityTranslator.INSTANCE;

    TranslationSettings settings = ConduitViaBootstrap.settings();
    TranslationSettings.TranslationEngine engine = settings.engine();
    boolean viaPossible = settings.enabled()
        && engine != TranslationSettings.TranslationEngine.NATIVE
        && ConduitViaSupport.supportsTranslation(clientProtocol, backendProtocol);
    boolean nativePossible = ProtocolDefinition.hasCodec(clientProtocol)
        && ProtocolDefinition.hasCodec(backendProtocol)
        && TranslatorRegistry.has(clientProtocol, backendProtocol);

    if (engine == TranslationSettings.TranslationEngine.VIA) {
      if (!viaPossible) {
        throw new IllegalArgumentException("ViaVersion cannot translate " + clientProtocol + " → " + backendProtocol);
      }
      return ConduitViaTranslator.create(clientProtocol, backendProtocol, host, port);
    }
    if (engine == TranslationSettings.TranslationEngine.NATIVE) {
      return TranslatorRegistry.find(clientProtocol, backendProtocol)
          .orElseThrow(() -> new IllegalArgumentException(
              "no packet translator for " + clientProtocol + " → " + backendProtocol));
    }
    // via-preferred
    if (viaPossible) {
      return ConduitViaTranslator.create(clientProtocol, backendProtocol, host, port);
    }
    if (nativePossible) {
      return TranslatorRegistry.find(clientProtocol, backendProtocol)
          .orElseThrow(() -> new IllegalArgumentException(
              "no packet translator for " + clientProtocol + " → " + backendProtocol));
    }
    throw new IllegalArgumentException(
        "no packet translator for " + clientProtocol + " → " + backendProtocol + " (" + support + ")");
  }
}
