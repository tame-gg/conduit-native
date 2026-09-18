// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol.semantic;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;

/**
 * Version-agnostic "initialize the world border" operation.
 *
 * <p>The wire forms differ structurally by era:
 * <ul>
 *   <li>1.13 (393) carries every border operation in one packet prefixed by an action enum,
 *       where the INITIALIZE action orders {@code warningTime} before {@code warningBlocks}.</li>
 *   <li>1.17+ (including 765) splits the operations into separate packets, and the
 *       Initialize World Border packet orders {@code warningBlocks} before {@code warningTime}.</li>
 * </ul>
 * Keeping the fields named here is what prevents the two warning values from being swapped
 * when a body is moved between eras.
 */
public record WorldBorderInitPacket(
    PacketDirection direction,
    double centerX,
    double centerZ,
    double oldDiameter,
    double newDiameter,
    long speedMillis,
    int portalTeleportBoundary,
    int warningBlocks,
    int warningTime
) implements SemanticPacket {
  @Override public PacketKind kind() { return PacketKind.PLAY_WORLD_BORDER_INIT; }

  @Override public ConnectionState state() { return ConnectionState.PLAY; }
}
