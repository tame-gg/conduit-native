package gg.tame.conduit.tests;

import gg.tame.conduit.login.LoginStart;
import gg.tame.conduit.login.PlayerProfile;
import gg.tame.conduit.protocol.CompatibilityCompleteness;
import gg.tame.conduit.protocol.CompatibilityRegistry;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.PluginMessage;
import gg.tame.conduit.protocol.ProtocolCompatibility;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.ProtocolTranslator;
import gg.tame.conduit.protocol.TranslationSupport;
import gg.tame.conduit.protocol.Translators;
import gg.tame.conduit.protocol.codec.JoinGameCodec;
import gg.tame.conduit.protocol.semantic.JoinGamePacket;
import gg.tame.conduit.protocol.semantic.KeepAlivePacket;
import gg.tame.conduit.protocol.semantic.LoginSuccessPacket;
import gg.tame.conduit.protocol.semantic.MovementPacket;
import gg.tame.conduit.protocol.semantic.PlayerPositionPacket;
import gg.tame.conduit.protocol.translate.ConfigurationAbsorber;
import gg.tame.conduit.protocol.translate.TranslationException;
import java.util.List;
import java.util.UUID;

/** Phase 17 — 393 ↔ 765 translation foundation and Configuration state bridge. */
public final class Phase17_393_765_TranslationTests {
  private Phase17_393_765_TranslationTests() {}

  public static void run() throws Exception {
    compatibility();
    loginStartRoundTrip();
    loginSuccessRoundTrip();
    joinGameRoundTrip();
    keepAlive();
    pluginMessage();
    movement();
    playerPosition();
    configurationDroppedTo393();
    configurationAbsorberKeepAliveAndFinish();
    disconnect();
    unsupportedChunksFailClosed();
    differenceDatabase();
    System.out.println("Phase17_393_765_TranslationTests passed.");
  }

  private static void compatibility() {
    require(ProtocolCompatibility.between(393, 765) == TranslationSupport.TRANSLATED, "translated");
    require(CompatibilityRegistry.resolve(393, 765).completeness() == CompatibilityCompleteness.PARTIAL, "partial");
    require(CompatibilityRegistry.resolve(765, 776).support() == TranslationSupport.UNSUPPORTED, "765-776 unchanged");
    ProtocolTranslator t = Translators.forPair(393, 765);
    require(t != null, "translator registered");
  }

  private static void loginStartRoundTrip() throws Exception {
    ProtocolDefinition v393 = ProtocolDefinition.forVersion(393);
    ProtocolDefinition v765 = ProtocolDefinition.forVersion(765);
    PlayerProfile profile = new PlayerProfile(UUID.randomUUID(), "Steve", List.of(), true);
    byte[] from393 = LoginStart.encode(profile, v393);
    ProtocolTranslator t = Translators.forPair(393, 765);
    byte[] to765 = t.clientToBackend(ConnectionState.LOGIN, from393);
    require(PlayPackets.packetId(to765) == 0, "login start id");
    require(PlayPackets.body(to765).length > PlayPackets.body(from393).length, "765 adds uuid");
    var decoded = JoinGameCodec.decodeLoginStart(v765, PlayPackets.body(to765));
    require(decoded.username().equals("Steve"), "username");
    require(decoded.clientUuid().isPresent(), "uuid present on 765");
  }

  private static void loginSuccessRoundTrip() throws Exception {
    ProtocolDefinition v393 = ProtocolDefinition.forVersion(393);
    ProtocolDefinition v765 = ProtocolDefinition.forVersion(765);
    LoginSuccessPacket semantic = new LoginSuccessPacket(PacketDirection.SERVER_TO_CLIENT,
        UUID.fromString("11111111-1111-1111-1111-111111111111"), "Alex", List.of());
    byte[] from765 = JoinGameCodec.encodeLoginSuccess(v765, semantic);
    ProtocolTranslator t = Translators.forPair(393, 765);
    byte[] to393 = t.backendToClient(ConnectionState.LOGIN, from765);
    LoginSuccessPacket back = JoinGameCodec.decodeLoginSuccess(v393, to393);
    require(back.uniqueId().equals(semantic.uniqueId()), "uuid");
    require(back.username().equals("Alex"), "name");
  }

  private static void joinGameRoundTrip() throws Exception {
    JoinGamePacket join = new JoinGamePacket(PacketDirection.SERVER_TO_CLIENT, 42, false, 1, -1, 0,
        "minecraft:overworld", "minecraft:overworld", List.of("minecraft:overworld"), 123L,
        20, 10, 8, false, true, false, false, (byte) 1, "default", false, 0);
    ProtocolDefinition v765 = ProtocolDefinition.forVersion(765);
    ProtocolDefinition v393 = ProtocolDefinition.forVersion(393);
    byte[] modern = JoinGameCodec.encode(v765, join);
    ProtocolTranslator t = Translators.forPair(393, 765);
    byte[] legacy = t.backendToClient(ConnectionState.PLAY, modern);
    JoinGamePacket decoded = JoinGameCodec.decode(v393, legacy);
    require(decoded.entityId() == 42, "entity");
    require(decoded.gameMode() == 1, "gamemode");
    require(decoded.dimensionId() == 0, "dimension");
    require(PlayPackets.packetId(legacy) == 0x25, "393 join id");
  }

  private static void keepAlive() throws Exception {
    ProtocolTranslator t = Translators.forPair(393, 765);
    KeepAlivePacket keep = new KeepAlivePacket(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, 0x7fff_ffff_ffff_ffffL);
    byte[] from765 = new gg.tame.conduit.protocol.codec.SemanticCodec(ProtocolDefinition.forVersion(765), 64).encode(keep);
    byte[] to393 = t.backendToClient(ConnectionState.PLAY, from765);
    KeepAlivePacket decoded = (KeepAlivePacket) new gg.tame.conduit.protocol.codec.SemanticCodec(
        ProtocolDefinition.forVersion(393), 64).decode(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, to393);
    require(decoded.id() == 0x7fff_ffff_ffff_ffffL, "keepalive full long");
  }

  private static void pluginMessage() throws Exception {
    ProtocolTranslator t = Translators.forPair(393, 765);
    byte[] brand = new PluginMessage("minecraft:brand", PluginMessage.brandPayload("Paper")).encode(
        ProtocolDefinition.forVersion(765).id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLUGIN_MESSAGE));
    byte[] to393 = t.backendToClient(ConnectionState.PLAY, brand);
    require(PlayPackets.packetId(to393) == 0x19, "393 plugin id");
  }

  private static void movement() throws Exception {
    ProtocolTranslator t = Translators.forPair(393, 765);
    MovementPacket move = MovementPacket.positionLook(1.5, 64.0, -3.25, 90f, 0f, true);
    byte[] from393 = JoinGameCodec.encodeMovement(ProtocolDefinition.forVersion(393), move);
    byte[] to765 = t.clientToBackend(ConnectionState.PLAY, from393);
    MovementPacket decoded = JoinGameCodec.decodeMovement(PacketKind.PLAY_POSITION_LOOK, to765);
    require(decoded.x() == 1.5 && decoded.onGround(), "movement fields");
    require(PlayPackets.packetId(to765) == 0x18, "765 position_look id");
  }

  private static void playerPosition() throws Exception {
    ProtocolTranslator t = Translators.forPair(393, 765);
    PlayerPositionPacket pos = new PlayerPositionPacket(PacketDirection.SERVER_TO_CLIENT, 0, 70, 0, 0, 0, (byte) 0, 7);
    byte[] from765 = JoinGameCodec.encodePlayerPosition(ProtocolDefinition.forVersion(765), pos);
    byte[] to393 = t.backendToClient(ConnectionState.PLAY, from765);
    PlayerPositionPacket decoded = JoinGameCodec.decodePlayerPosition(to393);
    require(decoded.teleportId() == 7 && decoded.y() == 70, "teleport");
    require(PlayPackets.packetId(to393) == 0x32, "393 position id");
  }

  private static void configurationDroppedTo393() throws Exception {
    ProtocolTranslator t = Translators.forPair(393, 765);
    byte[] finish = PlayPackets.withId(
        ProtocolDefinition.forVersion(765).id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_FINISH),
        new byte[0]);
    require(t.backendToClient(ConnectionState.CONFIGURATION, finish) == null, "finish dropped");
    byte[] registry = PlayPackets.withId(0x05, new byte[] {0});
    require(t.backendToClient(ConnectionState.CONFIGURATION, registry) == null, "registry dropped");
  }

  private static void configurationAbsorberKeepAliveAndFinish() throws Exception {
    ProtocolDefinition v765 = ProtocolDefinition.forVersion(765);
    ConfigurationAbsorber absorber = new ConfigurationAbsorber(v765);
    KeepAlivePacket keep = new KeepAlivePacket(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, 99L);
    byte[] keepWire = new gg.tame.conduit.protocol.codec.SemanticCodec(v765, 64).encode(keep);
    var response = absorber.onBackendPacket(keepWire);
    require(response.isPresent(), "keepalive response");
    byte[] finish = PlayPackets.withId(v765.id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_FINISH), new byte[0]);
    var finishAck = absorber.onBackendPacket(finish);
    require(finishAck.isPresent() && absorber.finished(), "finish ack");
  }

  private static void disconnect() throws Exception {
    ProtocolTranslator t = Translators.forPair(393, 765);
    byte[] kick = JoinGameCodec.encodeDisconnect(ProtocolDefinition.forVersion(765),
        new gg.tame.conduit.protocol.semantic.DisconnectPacket(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT,
            PacketKind.PLAY_DISCONNECT, "bye"));
    byte[] to393 = t.backendToClient(ConnectionState.PLAY, kick);
    require(PlayPackets.packetId(to393) == 0x1B, "393 disconnect id");
  }

  private static void unsupportedChunksFailClosed() {
    ProtocolTranslator t = Translators.forPair(393, 765);
    try {
      t.backendToClient(ConnectionState.PLAY, new byte[] {(byte) 0x22, 0x01}); // 393 map_chunk id toward wrong codec path
      // Using 765 unknown play id
    } catch (TranslationException expected) {
      require(expected.getMessage().contains("unsupported") || expected.getMessage().contains("not in"), "fail closed");
      return;
    }
    try {
      t.backendToClient(ConnectionState.PLAY, PlayPackets.withId(0x7E, new byte[] {1, 2, 3}));
      throw new AssertionError("unknown accepted");
    } catch (TranslationException | java.io.IOException expected) {
      require(true, "fail closed");
    }
  }

  private static void differenceDatabase() {
    require(gg.tame.conduit.protocol.diff.ProtocolDifferenceDatabase.between(393, 765).stream()
        .anyMatch(c -> c.packetOrArea().equals("CONFIGURATION")), "config documented");
    require(gg.tame.conduit.protocol.diff.ProtocolDifferenceDatabase.between(393, 765).stream()
        .anyMatch(c -> c.packetOrArea().equals("CHUNKS")), "chunks documented");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
