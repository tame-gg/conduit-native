// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol.semantic;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import java.util.List;
import java.util.function.IntUnaryOperator;

/**
 * Version-agnostic batch of block changes within one chunk column.
 *
 * <p>The wire forms are structurally unrelated:
 * <ul>
 *   <li><b>1.13 (393)</b> — chunk X and Z as Ints, then records of
 *       {@code (horizontal byte, absolute Y byte, VarInt state)}. Y is a single unsigned byte,
 *       so the packet can only address the 0..255 world.</li>
 *   <li><b>1.20.4 (765)</b> — one packed section coordinate (x:22, z:22, y:20), then records as
 *       VarLongs of {@code (state << 12) | (localX << 8) | (localZ << 4) | localY}, addressing a
 *       single 16-block-tall section anywhere in a taller world.</li>
 * </ul>
 *
 * <p>Positions are held as absolute world coordinates so neither packing leaks into the model.
 * Block states are held in the era they were decoded from and mapped at the translation boundary.
 */
public record SemanticBlockChanges(
    PacketDirection direction,
    int chunkX,
    int chunkZ,
    List<Change> changes
) implements SemanticPacket {
  /** One block change at absolute world coordinates. */
  public record Change(int x, int y, int z, int blockState) {}

  @Override public PacketKind kind() { return PacketKind.PLAY_MULTI_BLOCK_CHANGE; }

  @Override public ConnectionState state() { return ConnectionState.PLAY; }

  /** Returns a copy with every block state passed through {@code mapper}. */
  public SemanticBlockChanges mapStates(IntUnaryOperator mapper) {
    return new SemanticBlockChanges(direction, chunkX, chunkZ,
        changes.stream()
            .map(change -> new Change(change.x(), change.y(), change.z(),
                mapper.applyAsInt(change.blockState())))
            .toList());
  }
}
