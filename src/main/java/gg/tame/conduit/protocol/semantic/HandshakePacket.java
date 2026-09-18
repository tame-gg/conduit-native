// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol.semantic;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;

/** Handshake is directionally client→server in AWAITING_HANDSHAKE. */
public record HandshakePacket(int protocolVersion, String host, int port, int nextState) implements SemanticPacket {
  @Override public PacketKind kind() { return PacketKind.HANDSHAKE; }
  @Override public ConnectionState state() { return ConnectionState.AWAITING_HANDSHAKE; }
  @Override public PacketDirection direction() { return PacketDirection.CLIENT_TO_SERVER; }
}
