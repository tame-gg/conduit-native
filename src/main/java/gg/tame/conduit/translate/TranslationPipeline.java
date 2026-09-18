// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.translate;

import gg.tame.conduit.protocol.ProtocolCompatibility;
import gg.tame.conduit.protocol.ProtocolTranslator;
import gg.tame.conduit.protocol.TranslationSupport;
import gg.tame.conduit.protocol.Translators;

/**
 * Translation pipeline entry: decode → semantic → encode via {@link Translators}.
 * 765↔766 is TRANSLATED (PARTIAL play). 765↔776 remains UNSUPPORTED.
 */
public final class TranslationPipeline {
  private TranslationPipeline() {}
  public static ProtocolTranslator pipeline(int clientProtocol, int backendProtocol) {
    return Translators.forPair(clientProtocol, backendProtocol);
  }
  public static TranslationSupport support(int clientProtocol, int backendProtocol) {
    return ProtocolCompatibility.between(clientProtocol, backendProtocol);
  }
}
