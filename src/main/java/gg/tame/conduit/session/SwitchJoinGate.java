package gg.tame.conduit.session;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolDefinition;
import java.io.IOException;

/**
 * Holds a switched client with no Configuration phase back from the new backend until that
 * backend's Join Game has reached it.
 *
 * <p>The hold ends on the Join Game the client is actually written, not on what the translator
 * returned for the packet it was handed. Those are not the same packet. Switching a 1.8 client
 * onto a 1.13 backend, Via returns the translated Join Game as its result. Switching it back onto
 * a 1.20.4 backend, Via cancels the result and emits the 1.8 Join Game as a queued extra, because
 * its 1.20.2 downgrade rebuilds that packet from the Configuration phase it buffered. A hold that
 * looked only at the result never ended on that path: every client packet after the return was
 * dropped, Keep Alive responses with them, and the backend timed the player out thirty seconds
 * later while the client still showed the world.
 */
public final class SwitchJoinGate {
  private final ProtocolDefinition client;
  private volatile boolean holding;
  private byte[] joinGame;

  public SwitchJoinGate(ProtocolDefinition client) {
    this.client = client;
  }

  /** Starts holding the client back until a Join Game is written to it. */
  public synchronized void hold() {
    joinGame = null;
    holding = true;
  }

  /** Ends any hold without a Join Game, as a failed switch does. */
  public synchronized void release() {
    joinGame = null;
    holding = false;
  }

  /** True while the client's packets must not reach the new backend's translator. */
  public boolean holding() {
    return holding;
  }

  /** Every packet written to the client passes through here, whichever way the translator produced it. */
  public synchronized void written(byte[] packet) throws IOException {
    if (!holding || joinGame != null) return;
    if (client.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PlayPackets.packetId(packet), PacketKind.PLAY_LOGIN)) {
      joinGame = packet;
    }
  }

  /** The Join Game that ends the hold, exactly once; null until one has been written. */
  public synchronized byte[] takeJoinGame() {
    byte[] arrived = joinGame;
    if (arrived != null) {
      joinGame = null;
      holding = false;
    }
    return arrived;
  }
}
