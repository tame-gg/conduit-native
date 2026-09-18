// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol.semantic;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.chunk.SemanticChunk;

public record ChunkDataPacket(PacketDirection direction, SemanticChunk chunk) implements SemanticPacket {
  @Override public PacketKind kind() { return PacketKind.PLAY_CHUNK_DATA; }
  @Override public ConnectionState state() { return ConnectionState.PLAY; }
}
