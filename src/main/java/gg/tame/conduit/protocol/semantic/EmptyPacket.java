package gg.tame.conduit.protocol.semantic;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;

/** Empty-body control packets (finish configuration, start configuration, acks). */
public record EmptyPacket(PacketKind kind, ConnectionState state, PacketDirection direction) implements SemanticPacket {
  public EmptyPacket {
    if (kind == null || state == null || direction == null) throw new IllegalArgumentException("empty packet fields required");
  }
}
