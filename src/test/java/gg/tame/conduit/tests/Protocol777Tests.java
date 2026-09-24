// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.login.PlayerProfile;
import gg.tame.conduit.protocol.CodecStatus;
import gg.tame.conduit.protocol.CompatibilityRegistry;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.JoinGame;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolCatalog;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.ProtocolFamily;
import gg.tame.conduit.protocol.TranslationSupport;
import java.util.List;
import java.util.UUID;

/**
 * Minecraft 26.3 (protocol 777), native.
 *
 * <p>Every id asserted here is from Mojang's own packet report for 26.3 ({@code server.jar
 * --reports}), not from 26.2's table plus an assumed shift: the point of the test is that the
 * delta Conduit applies to 26.2 lands on those numbers, and that what 26.3 did not move is still
 * inherited.
 */
public final class Protocol777Tests {
  private static final ConnectionState CONFIG = ConnectionState.CONFIGURATION;
  private static final ConnectionState PLAY = ConnectionState.PLAY;
  private static final PacketDirection TO_CLIENT = PacketDirection.SERVER_TO_CLIENT;
  private static final PacketDirection TO_SERVER = PacketDirection.CLIENT_TO_SERVER;

  private Protocol777Tests() {}

  public static void main(String[] args) throws Exception {
    run();
  }

  public static void run() throws Exception {
    identity();
    moved();
    unchanged();
    joinGame();
    System.out.println("Protocol777Tests passed.");
  }

  private static void identity() {
    require(ProtocolCatalog.findRelease("26.3").orElseThrow().protocol() == 777, "26.3 is 777");
    require(ProtocolFamily.ofProtocol(777) == ProtocolFamily.V26, "777 is in the 26 family");
    require(ProtocolDefinition.hasCodec(777), "777 has a packet table");
    require(ProtocolDefinition.codecStatus(777) == CodecStatus.DECLARED, "777's table is declared from the report");
    var direct = CompatibilityRegistry.resolve(777, 777);
    require(direct.support() == TranslationSupport.DIRECT, "777 to 777 is DIRECT");
    require(direct.selectable(), "777 to 777 is selectable");
  }

  private static void moved() {
    ProtocolDefinition v777 = ProtocolDefinition.forVersion(777);
    // Configuration: post_effects went in at 0x0A.
    id(v777, CONFIG, TO_CLIENT, PacketKind.CONFIGURATION_STORE_COOKIE, 0x0B);
    id(v777, CONFIG, TO_CLIENT, PacketKind.CONFIGURATION_TRANSFER, 0x0C);
    id(v777, CONFIG, TO_CLIENT, PacketKind.CONFIGURATION_KNOWN_PACKS, 0x0F);
    id(v777, CONFIG, TO_CLIENT, PacketKind.CONFIGURATION_SERVER_LINKS, 0x11);
    // Play, clientbound: add_transient_block at 0x25, post_effects at 0x53, swing_animation at 0x7B.
    id(v777, PLAY, TO_CLIENT, PacketKind.PLAY_KEEP_ALIVE, 0x2D);
    id(v777, PLAY, TO_CLIENT, PacketKind.PLAY_LOGIN, 0x32);
    id(v777, PLAY, TO_CLIENT, PacketKind.PLAY_PLAYER_INFO_REMOVE, 0x46);
    id(v777, PLAY, TO_CLIENT, PacketKind.PLAY_PLAYER_INFO_UPDATE, 0x47);
    id(v777, PLAY, TO_CLIENT, PacketKind.PLAY_RESOURCE_PACK_POP, 0x51);
    id(v777, PLAY, TO_CLIENT, PacketKind.PLAY_RESOURCE_PACK_PUSH, 0x52);
    id(v777, PLAY, TO_CLIENT, PacketKind.PLAY_SET_ACTION_BAR, 0x59);
    id(v777, PLAY, TO_CLIENT, PacketKind.PLAY_SET_SUBTITLE, 0x72);
    id(v777, PLAY, TO_CLIENT, PacketKind.PLAY_SET_TITLE_TEXT, 0x74);
    id(v777, PLAY, TO_CLIENT, PacketKind.PLAY_SET_TITLE_TIMES, 0x75);
    id(v777, PLAY, TO_CLIENT, PacketKind.PLAY_ENTITY_SOUND_EFFECT, 0x76);
    id(v777, PLAY, TO_CLIENT, PacketKind.PLAY_SOUND_EFFECT, 0x77);
    id(v777, PLAY, TO_CLIENT, PacketKind.PLAY_START_CONFIGURATION, 0x78);
    id(v777, PLAY, TO_CLIENT, PacketKind.PLAY_STOP_SOUND, 0x79);
    id(v777, PLAY, TO_CLIENT, PacketKind.PLAY_STORE_COOKIE, 0x7A);
    id(v777, PLAY, TO_CLIENT, PacketKind.PLAY_SYSTEM_CHAT, 0x7C);
    id(v777, PLAY, TO_CLIENT, PacketKind.PLAY_TAB_LIST_HEADER, 0x7D);
    id(v777, PLAY, TO_CLIENT, PacketKind.PLAY_TRANSFER, 0x84);
    id(v777, PLAY, TO_CLIENT, PacketKind.PLAY_SERVER_LINKS, 0x8C);
    // Play, serverbound: punch went in at 0x2E and swing left, so only 0x2E-0x3E moved.
    id(v777, PLAY, TO_SERVER, PacketKind.PLAY_RESOURCE_PACK_STATUS, 0x32);
  }

  private static void unchanged() {
    ProtocolDefinition v777 = ProtocolDefinition.forVersion(777);
    ProtocolDefinition v776 = ProtocolDefinition.forVersion(776);
    id(v777, ConnectionState.LOGIN, TO_CLIENT, PacketKind.LOGIN_SUCCESS, 2);
    id(v777, CONFIG, TO_CLIENT, PacketKind.CONFIGURATION_REGISTRY, 0x07);
    id(v777, CONFIG, TO_CLIENT, PacketKind.CONFIGURATION_FINISH, 0x03);
    id(v777, CONFIG, TO_SERVER, PacketKind.CONFIGURATION_KNOWN_PACKS, 0x07);
    id(v777, PLAY, TO_CLIENT, PacketKind.PLAY_DECLARE_COMMANDS, 0x10);
    id(v777, PLAY, TO_CLIENT, PacketKind.PLAY_DISCONNECT, 0x20);
    id(v777, PLAY, TO_CLIENT, PacketKind.PLAY_BOSS_BAR, 0x09);
    id(v777, PLAY, TO_SERVER, PacketKind.PLAY_KEEP_ALIVE, 0x1C);
    id(v777, PLAY, TO_SERVER, PacketKind.PLAY_CHAT_COMMAND, 0x07);
    id(v777, PLAY, TO_SERVER, PacketKind.PLAY_CHAT, 0x09);
    id(v777, PLAY, TO_SERVER, PacketKind.PLAY_CONFIGURATION_ACKNOWLEDGED, 0x10);
    id(v777, PLAY, TO_SERVER, PacketKind.PLAY_PLUGIN_MESSAGE, 0x16);
    require(v777.capabilities().equals(v776.capabilities()), "777 keeps 26.2's capabilities");
  }

  /**
   * Join Game is the one 26.x play packet Conduit edits on the DIRECT path: the online-mode flag,
   * which 26.3 still writes second to last, ahead of enforcesSecureChat. The id is what moved.
   */
  private static void joinGame() throws Exception {
    ProtocolDefinition v777 = ProtocolDefinition.forVersion(777);
    PlayerProfile authenticated = new PlayerProfile(UUID.randomUUID(), "Joiner", List.of(), true);
    byte[] join = PlayPackets.withId(0x32, new byte[] {0, 0, 0, 1, 0, 0});
    byte[] marked = JoinGame.markOnlineMode(v777, join, authenticated);
    require(marked[marked.length - 2] == 1 && marked[marked.length - 1] == 0, "777 Join Game gets online mode");
    byte[] oldId = PlayPackets.withId(0x31, new byte[] {0, 0, 0, 1, 0, 0});
    require(JoinGame.markOnlineMode(v777, oldId, authenticated) == oldId, "26.2's Join Game id is not 777's");
  }

  private static void id(ProtocolDefinition protocol, ConnectionState state, PacketDirection direction,
                         PacketKind kind, int expected) {
    int actual = protocol.id(state, direction, kind);
    require(actual == expected, "777 " + state + "/" + direction + " " + kind + " = 0x"
        + Integer.toHexString(expected) + ", got 0x" + Integer.toHexString(actual));
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
