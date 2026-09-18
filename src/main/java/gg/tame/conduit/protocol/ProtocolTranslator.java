// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol;

import java.util.List;

/**
 * Cross-version packet translation operating on already framed, decompressed packets.
 * Identity only for DIRECT; pair translators for TRANSLATED.
 *
 * <p>Translation is not always one packet in, one packet out. 1.16 packed six
 * equipment slots into a packet that 1.13 can only express as six, and a 1.13
 * client will not touch its inventory again until it receives the transaction
 * confirmation that a 1.20.4 server has no packet for. A translator therefore
 * may queue extra packets while handling one, and the session drains them
 * immediately afterwards, in order. The queues are per-session, which is why
 * translators are instantiated per session rather than shared.
 */
public interface ProtocolTranslator {
  byte[] clientToBackend(ConnectionState state, byte[] packet);
  byte[] backendToClient(ConnectionState state, byte[] packet);

  /** Extra packets to send to the client, produced while handling the last one. */
  default List<byte[]> drainToClient() { return List.of(); }

  /** Extra packets to send to the backend, produced while handling the last one. */
  default List<byte[]> drainToBackend() { return List.of(); }
}
