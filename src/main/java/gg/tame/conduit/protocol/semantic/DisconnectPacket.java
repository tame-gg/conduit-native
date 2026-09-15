package gg.tame.conduit.protocol.semantic;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;

/** Disconnect / kick with plain text (no secrets). */
public record DisconnectPacket(
    ConnectionState state,
    PacketDirection direction,
    PacketKind kind,
    String reason
) implements SemanticPacket {
  public DisconnectPacket {
    if (reason == null) reason = "";
  }
}
