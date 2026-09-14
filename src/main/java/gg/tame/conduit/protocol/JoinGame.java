package gg.tame.conduit.protocol;

import gg.tame.conduit.login.PlayerProfile;
import java.io.IOException;

/**
 * Restores the authenticated online-mode flag in the clientbound Join Game packet.
 *
 * <p>26.2's {@code ClientboundLoginPacket} carries an {@code onlineMode} boolean, and the client
 * draws player-list faces only when it is set: {@code PlayerTabOverlay.render} reads
 * {@code ClientPacketListener.onlineMode()} and skips the face entirely when it is false, while
 * still drawing the name and ping. Backends behind modern forwarding run {@code online-mode=false}
 * and therefore report false, so every TAB face disappeared even though the player-info entry and
 * its signed textures were correct.
 *
 * <p>Conduit performed the Mojang session handshake itself, so for an authenticated profile the
 * flag is true from the client's point of view; the backend simply has no way to know that. It is
 * never set for an unauthenticated (offline-mode) session.
 *
 * <p>{@code onlineMode} and {@code enforcesSecureChat} are the last two fields the client writes,
 * both single-byte booleans, so the flag is the second-to-last byte. That avoids decoding
 * {@code CommonPlayerSpawnInfo}, whose layout changes between versions. Both bytes are checked to
 * be valid booleans first; anything else leaves the packet alone.
 */
public final class JoinGame {
  private JoinGame() {}
  public static byte[] markOnlineMode(ProtocolDefinition protocol, byte[] packet, PlayerProfile profile) throws IOException {
    if (!protocol.capabilities().joinGameOnlineMode()) return packet;
    if (!profile.authenticated()) return packet;
    if (!protocol.defines(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN)) return packet;
    if (!protocol.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PlayPackets.packetId(packet), PacketKind.PLAY_LOGIN)) return packet;
    int onlineMode = packet.length - 2;
    if (onlineMode < 1) return packet;
    if (!isBoolean(packet[onlineMode]) || !isBoolean(packet[packet.length - 1])) return packet;
    if (packet[onlineMode] == 1) return packet;
    byte[] rewritten = packet.clone();
    rewritten[onlineMode] = 1;
    return rewritten;
  }
  private static boolean isBoolean(byte value) { return value == 0 || value == 1; }
}
