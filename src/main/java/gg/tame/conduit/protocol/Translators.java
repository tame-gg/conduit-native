package gg.tame.conduit.protocol;

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
    CompatibilityProbe.Result probe = CompatibilityProbe.probe(clientProtocol, backendProtocol);
    return switch (probe.engine()) {
      case DIRECT -> IdentityTranslator.INSTANCE;
      case VIA -> ConduitViaTranslator.create(clientProtocol, backendProtocol, host, port);
      case NATIVE -> TranslatorRegistry.find(clientProtocol, backendProtocol)
          .orElseThrow(() -> new IllegalArgumentException(
              "no packet translator for " + clientProtocol + " → " + backendProtocol));
      case NONE -> throw new IllegalArgumentException("no packet translator for "
          + clientProtocol + " → " + backendProtocol + " (" + probe.reason() + ")");
    };
  }
}
