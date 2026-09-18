// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol.semantic;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;

public record PluginMessagePacket(ConnectionState state, PacketDirection direction, String channel, byte[] data) implements SemanticPacket {
  public PluginMessagePacket {
    if (state == null || direction == null) throw new IllegalArgumentException("plugin message state/direction required");
    if (channel == null || channel.isBlank()) throw new IllegalArgumentException("plugin channel required");
    if (data == null) data = new byte[0];
    else data = data.clone();
  }
  @Override public PacketKind kind() {
    return state == ConnectionState.CONFIGURATION ? PacketKind.CONFIGURATION_PLUGIN_MESSAGE : PacketKind.PLAY_PLUGIN_MESSAGE;
  }
}
