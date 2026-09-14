package gg.tame.conduit.protocol;

/**
 * Cross-version packet translation operating on already framed, decompressed packets.
 * Identity only for DIRECT; pair translators for TRANSLATED.
 */
public interface ProtocolTranslator {
  byte[] clientToBackend(ConnectionState state, byte[] packet);
  byte[] backendToClient(ConnectionState state, byte[] packet);
}
