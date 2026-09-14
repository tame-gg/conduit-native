package gg.tame.conduit.translate;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.ProtocolCompatibility;
import gg.tame.conduit.protocol.ProtocolTranslator;
import gg.tame.conduit.protocol.TranslationSupport;
import gg.tame.conduit.protocol.Translators;

/**
 * Translation pipeline: decode client protocol → semantic model → encode backend protocol.
 * 765↔776 is not implemented; ProtocolCompatibility still reports UNSUPPORTED.
 */
public final class TranslationPipeline {
  private TranslationPipeline() {}
  public static ProtocolTranslator pipeline(int clientProtocol, int backendProtocol) {
    return Translators.forPair(clientProtocol, backendProtocol);
  }
  public static TranslationSupport support(int clientProtocol, int backendProtocol) {
    return ProtocolCompatibility.between(clientProtocol, backendProtocol);
  }
  /** Placeholder semantic packet. Real codecs will fill this; identity forwarding does not use it. */
  public record SemanticPacket(ConnectionState state, String name, byte[] payload) {}
}
