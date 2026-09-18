// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol.semantic;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;

/**
 * Version-agnostic Minecraft operation. Wire IDs live only in ProtocolDefinition codecs.
 */
public interface SemanticPacket {
  PacketKind kind();
  ConnectionState state();
  PacketDirection direction();
}
