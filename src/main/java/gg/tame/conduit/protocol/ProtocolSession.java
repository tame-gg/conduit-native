// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol;

/** Owns state transitions independently from transport. */
public final class ProtocolSession {
  private ConnectionState state = ConnectionState.AWAITING_HANDSHAKE;
  public ConnectionState state() { return state; }
  public void acceptHandshake(int nextState) {
    if (state != ConnectionState.AWAITING_HANDSHAKE) throw new IllegalStateException("handshake is not valid in " + state);
    state = switch (nextState) { case 1 -> ConnectionState.STATUS; case 2, Handshake.TRANSFER -> ConnectionState.LOGIN; default -> throw new IllegalArgumentException("unsupported handshake target: " + nextState); };
  }
  public void close() { state = ConnectionState.CLOSED; }
  public void beginConfiguration() { transition(ConnectionState.LOGIN, ConnectionState.CONFIGURATION); }
  public void enterPlayFromLogin() { transition(ConnectionState.LOGIN, ConnectionState.PLAY); }
  public void beginReconfiguration() { transition(ConnectionState.PLAY, ConnectionState.CONFIGURATION); }
  public void beginPlay() { transition(ConnectionState.CONFIGURATION, ConnectionState.PLAY); }
  private void transition(ConnectionState expected, ConnectionState target) {
    if (state != expected) throw new IllegalStateException("transition to " + target + " is not valid in " + state);
    state = target;
  }
}
