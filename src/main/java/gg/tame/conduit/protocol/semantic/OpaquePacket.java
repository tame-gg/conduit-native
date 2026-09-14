package gg.tame.conduit.protocol.semantic;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;

/** Opaque body retained when layout is identical across a translation pair. */
public record OpaquePacket(PacketKind kind, ConnectionState state, PacketDirection direction, byte[] body) implements SemanticPacket {
  public OpaquePacket {
    if (kind == null || state == null || direction == null) throw new IllegalArgumentException("opaque packet fields required");
    if (body == null) body = new byte[0];
    else body = body.clone();
  }
}
