// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.session;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolDefinition;
import java.io.IOException;
import java.util.Arrays;

/**
 * A player's latency, measured the way the game measures it: the round trip of a keep-alive, from
 * the moment Conduit writes it to the client to the moment the client's answer arrives. The answer
 * repeats the question's body in whatever layout the client's version uses (an int, a VarInt or a
 * long), so the bodies are compared rather than decoded. Only the latest question is remembered: a
 * client answers them in order, and one that skips an answer is measured on the next.
 */
final class KeepAliveClock {
  private final ProtocolDefinition protocol;
  private volatile byte[] asked;
  private volatile long askedAt;
  private volatile long latencyMillis = -1;

  KeepAliveClock(ProtocolDefinition protocol) { this.protocol = protocol; }

  /** A packet on its way to the client. */
  void written(ConnectionState state, byte[] packet) {
    if (state != ConnectionState.PLAY || !is(PacketDirection.SERVER_TO_CLIENT, packet)) return;
    try {
      byte[] body = PlayPackets.body(packet);
      askedAt = System.nanoTime();
      asked = body;
    } catch (IOException unreadable) { }
  }

  /** A packet from the client. */
  void read(ConnectionState state, byte[] packet) {
    byte[] question = asked;
    if (question == null || state != ConnectionState.PLAY || !is(PacketDirection.CLIENT_TO_SERVER, packet)) return;
    try {
      if (!Arrays.equals(question, PlayPackets.body(packet))) return;
    } catch (IOException unreadable) { return; }
    latencyMillis = Math.max(0, (System.nanoTime() - askedAt) / 1_000_000);
    asked = null;
  }

  /** The last round trip in milliseconds, or -1 before the client has answered one. */
  long latencyMillis() { return latencyMillis; }

  private boolean is(PacketDirection direction, byte[] packet) {
    if (!protocol.defines(ConnectionState.PLAY, direction, PacketKind.PLAY_KEEP_ALIVE)) return false;
    try { return protocol.is(ConnectionState.PLAY, direction, PlayPackets.peekId(packet), PacketKind.PLAY_KEEP_ALIVE); }
    catch (IOException unreadable) { return false; }
  }
}
