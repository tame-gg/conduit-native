// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol.codec;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.PluginMessage;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.semantic.EmptyPacket;
import gg.tame.conduit.protocol.semantic.KeepAlivePacket;
import gg.tame.conduit.protocol.semantic.OpaquePacket;
import gg.tame.conduit.protocol.semantic.PluginMessagePacket;
import gg.tame.conduit.protocol.semantic.SemanticPacket;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Optional;

/** Encode/decode Conduit-known semantic packets for a specific ProtocolDefinition. */
public final class SemanticCodec {
  private final ProtocolDefinition protocol;
  private final int maxPayload;

  public SemanticCodec(ProtocolDefinition protocol, int maxPayload) {
    this.protocol = protocol;
    this.maxPayload = Math.max(64, maxPayload);
  }

  public ProtocolDefinition protocol() { return protocol; }

  public Optional<PacketKind> identify(ConnectionState state, PacketDirection direction, int id) {
    for (PacketKind kind : PacketKind.values()) {
      if (protocol.is(state, direction, id, kind)) return Optional.of(kind);
    }
    return Optional.empty();
  }

  public SemanticPacket decode(ConnectionState state, PacketDirection direction, byte[] packet) throws IOException {
    int id = PlayPackets.packetId(packet);
    PacketKind kind = identify(state, direction, id)
        .orElseThrow(() -> new IOException("unknown packet id 0x" + Integer.toHexString(id) + " in " + state));
    byte[] body = PlayPackets.body(packet);
    return switch (kind) {
      case CONFIGURATION_FINISH, PLAY_START_CONFIGURATION, PLAY_CONFIGURATION_ACKNOWLEDGED, LOGIN_ACKNOWLEDGED ->
          new EmptyPacket(kind, state, direction);
      case CONFIGURATION_PLUGIN_MESSAGE, PLAY_PLUGIN_MESSAGE -> {
        PluginMessage message = PluginMessage.decodeBody(body, maxPayload);
        yield new PluginMessagePacket(state, direction, message.channel(), message.data());
      }
      case CONFIGURATION_KEEP_ALIVE, PLAY_KEEP_ALIVE -> {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
          yield new KeepAlivePacket(state, direction, input.readLong());
        }
      }
      default -> new OpaquePacket(kind, state, direction, body);
    };
  }

  public byte[] encode(SemanticPacket semantic) throws IOException {
    PacketKind kind = semantic.kind();
    ConnectionState state = semantic.state();
    PacketDirection direction = semantic.direction();
    if (!protocol.defines(state, direction, kind)) {
      throw new IOException("target protocol " + protocol.version().number() + " does not define " + kind);
    }
    int id = protocol.id(state, direction, kind);
    if (semantic instanceof EmptyPacket) {
      return PlayPackets.withId(id, new byte[0]);
    }
    if (semantic instanceof PluginMessagePacket plugin) {
      return new PluginMessage(plugin.channel(), plugin.data()).encode(id);
    }
    if (semantic instanceof KeepAlivePacket keepAlive) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        MinecraftOutput.varInt(output, id);
        output.writeLong(keepAlive.id());
      }
      return bytes.toByteArray();
    }
    if (semantic instanceof OpaquePacket opaque) {
      return PlayPackets.withId(id, opaque.body());
    }
    throw new IOException("unsupported semantic packet: " + semantic.getClass().getSimpleName());
  }

  public byte[] remapId(ConnectionState state, PacketDirection direction, PacketKind kind, byte[] sourcePacket) throws IOException {
    if (!protocol.defines(state, direction, kind)) {
      throw new IOException("cannot remap undefined " + kind);
    }
    return PlayPackets.withId(protocol.id(state, direction, kind), PlayPackets.body(sourcePacket));
  }
}
