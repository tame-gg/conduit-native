package gg.tame.conduit.protocol;

import gg.tame.conduit.login.PlayerProfile;
import gg.tame.conduit.metrics.ConduitMetrics;
import java.io.IOException;

/**
 * Applies the native authenticated profile to protocol-specific clientbound packets.
 *
 * <p>Every rewrite is scoped to the client's current {@link ConnectionState}. Packet ids are only
 * unique within a state, so matching on the id alone lets a Configuration or Play packet that
 * happens to share an id with Login Success (id 2 on both 765 and 776) be parsed as a Game Profile
 * and rewritten into garbage.
 */
public final class ProtocolProfileAdapter {
  private ProtocolProfileAdapter() {}
  public static byte[] backendToClient(ProtocolDefinition protocol, ConnectionState state, byte[] packet, PlayerProfile profile) {
    try {
      if (state == ConnectionState.LOGIN) return LoginSuccess.replaceProfile(protocol, packet, profile);
      if (state == ConnectionState.PLAY) {
        int id = PlayPackets.peekId(packet);
        if (protocol.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.PLAY_LOGIN)) {
          return JoinGame.markOnlineMode(protocol, packet, profile);
        }
        if (protocol.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.PLAY_PLAYER_INFO_UPDATE)) {
          return PlayerInfoUpdate.ensureOwnTextures(protocol, packet, profile);
        }
      }
    } catch (IOException exception) {
      ConduitMetrics.current().decodeFailure();
    }
    return packet;
  }
}
