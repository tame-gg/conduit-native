// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol.semantic;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import java.util.Optional;
import java.util.UUID;

/** Login Start — username always; UUID only when the protocol carries it. */
public record LoginStartPacket(
    PacketDirection direction,
    String username,
    Optional<UUID> clientUuid
) implements SemanticPacket {
  public LoginStartPacket {
    if (username == null || username.isBlank()) throw new IllegalArgumentException("username required");
    if (clientUuid == null) clientUuid = Optional.empty();
  }

  @Override public PacketKind kind() { return PacketKind.LOGIN_START; }
  @Override public ConnectionState state() { return ConnectionState.LOGIN; }
}
