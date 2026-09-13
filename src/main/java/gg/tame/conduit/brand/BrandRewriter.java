package gg.tame.conduit.brand;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PluginMessage;
import gg.tame.conduit.protocol.ProtocolDefinition;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Optional;

public final class BrandRewriter {
  private BrandRewriter() {}
  public static Optional<byte[]> rewrite(ProtocolDefinition protocol, ConnectionState state, byte[] packet, int maximumBytes) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      int id = MinecraftInput.varInt(input);
      PacketKind kind = kindFor(protocol, state, id);
      if (kind == null) return Optional.empty();
      PluginMessage message = PluginMessage.decodeBody(input.readAllBytes(), maximumBytes);
      if (!ServerBrand.CHANNEL.equals(message.channel())) return Optional.empty();
      String rewritten = ServerBrand.display(message.brandText());
      return Optional.of(new PluginMessage(ServerBrand.CHANNEL, PluginMessage.brandPayload(rewritten)).encode(id));
    }
  }
  public static byte[] synthesize(ProtocolDefinition protocol, ConnectionState state, String backendBrand) throws IOException {
    int id = protocol.id(state, PacketDirection.SERVER_TO_CLIENT,
        state == ConnectionState.CONFIGURATION ? PacketKind.CONFIGURATION_PLUGIN_MESSAGE : PacketKind.PLAY_PLUGIN_MESSAGE);
    return new PluginMessage(ServerBrand.CHANNEL, PluginMessage.brandPayload(ServerBrand.display(backendBrand))).encode(id);
  }
  private static PacketKind kindFor(ProtocolDefinition protocol, ConnectionState state, int id) {
    if (state == ConnectionState.CONFIGURATION && protocol.is(state, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.CONFIGURATION_PLUGIN_MESSAGE)) {
      return PacketKind.CONFIGURATION_PLUGIN_MESSAGE;
    }
    if (state == ConnectionState.PLAY && protocol.is(state, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.PLAY_PLUGIN_MESSAGE)) {
      return PacketKind.PLAY_PLUGIN_MESSAGE;
    }
    return null;
  }
}
