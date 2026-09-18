// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.viaversion;

import com.viaversion.viaversion.api.protocol.packet.State;
import gg.tame.conduit.protocol.ConnectionState;

/** Maps Conduit connection states to Via protocol states. */
public final class ConduitViaStates {
  private ConduitViaStates() {}

  public static State toVia(ConnectionState state) {
    if (state == null) return State.PLAY;
    return switch (state) {
      case AWAITING_HANDSHAKE, STATUS -> State.STATUS;
      case LOGIN -> State.LOGIN;
      case CONFIGURATION -> State.CONFIGURATION;
      case PLAY -> State.PLAY;
      case CLOSED -> State.PLAY;
    };
  }
}
