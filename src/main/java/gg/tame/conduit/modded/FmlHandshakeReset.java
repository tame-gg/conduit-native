package gg.tame.conduit.modded;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.ProtocolDefinition;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Optional;

/**
 * Forge 1.7.2-1.12.2's {@code FML|HS} HandshakeReset, discriminator -2.
 *
 * <p>FML1 runs its whole handshake in the Play phase over {@code FML|HS}, and a client that has
 * completed it does not run it again. Forge added this packet so a proxy could put the client back
 * to the start before a second server's handshake. Without it a client moved between two Forge
 * servers keeps the first server's mod and registry state.
 *
 * <p>Authoring only. Whether the exchange that follows the reset completes through Conduit has not
 * been driven against a Forge client or a Forge server.
 */
public final class FmlHandshakeReset {
  public static final String CHANNEL = "FML|HS";
  public static final byte HANDSHAKE_RESET = -2;

  private FmlHandshakeReset() {}

  /**
   * The reset packet for a switching client, or empty when the client is not FML1.
   *
   * <p>FML2 and later negotiate in the login or configuration phase and have no equivalent: a
   * reset is meaningless to them, which is why the marker decides and not the family.
   */
  public static Optional<byte[]> forSwitch(ProtocolDefinition protocol, FmlAddressMarkers.MarkerKind marker) throws IOException {
    if (marker != FmlAddressMarkers.MarkerKind.FML1) return Optional.empty();
    if (!protocol.defines(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLUGIN_MESSAGE)) {
      return Optional.empty();
    }
    int id = protocol.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLUGIN_MESSAGE);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, id);
      MinecraftOutput.string(output, CHANNEL);
      // 1.7 lengths its payload with a short; 1.8 dropped that and runs the data to the end of the
      // packet. PluginMessage only writes the 1.8 layout, so this cannot go through it.
      if (protocol.version().number() < 47) output.writeShort(1);
      output.writeByte(HANDSHAKE_RESET);
    }
    return Optional.of(bytes.toByteArray());
  }
}
