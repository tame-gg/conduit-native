package gg.tame.conduit.protocol;

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
    return CompatibilityProbe.probe(clientProtocol, backendProtocol).support();
  }
}
