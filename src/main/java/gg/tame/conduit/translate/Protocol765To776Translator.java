// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.translate;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.ProtocolTranslator;
import gg.tame.conduit.protocol.TranslationSupport;

/**
 * Intended first pair. Not implemented: packet layouts differ (known packs, hello boolean, ids).
 * Do not report TRANSLATED until a real client on 765 talks to a 776 backend through this class.
 */
public final class Protocol765To776Translator implements ProtocolTranslator {
  @Override public byte[] clientToBackend(ConnectionState state, byte[] packet) {
    throw new UnsupportedOperationException("765→776 translation is not implemented");
  }
  @Override public byte[] backendToClient(ConnectionState state, byte[] packet) {
    throw new UnsupportedOperationException("765→776 translation is not implemented");
  }
  public TranslationSupport support() { return TranslationSupport.UNSUPPORTED; }
}
