package gg.tame.conduit.protocol.semantic;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;

public record KeepAlivePacket(ConnectionState state, PacketDirection direction, long id) implements SemanticPacket {
  @Override public PacketKind kind() {
    return state == ConnectionState.CONFIGURATION ? PacketKind.CONFIGURATION_KEEP_ALIVE : PacketKind.PLAY_KEEP_ALIVE;
  }
}
