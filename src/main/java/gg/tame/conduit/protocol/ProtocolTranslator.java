package gg.tame.conduit.protocol;

/**
 * Cross-version packet translation. Identity only until a real translator exists.
 * Do not report TRANSLATED in ProtocolCompatibility until a non-identity implementation is tested.
 */
public interface ProtocolTranslator {
  byte[] clientToBackend(ConnectionState state, byte[] packet);
  byte[] backendToClient(ConnectionState state, byte[] packet);
}
