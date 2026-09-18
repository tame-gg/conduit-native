// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.IdentityTranslator;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.PluginMessage;
import gg.tame.conduit.protocol.ProtocolCatalog;
import gg.tame.conduit.protocol.ProtocolCompatibility;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.ProtocolTranslator;
import gg.tame.conduit.protocol.TranslationSupport;
import gg.tame.conduit.protocol.Translators;
import gg.tame.conduit.protocol.codec.SemanticCodec;
import gg.tame.conduit.protocol.semantic.EmptyPacket;
import gg.tame.conduit.protocol.semantic.KeepAlivePacket;
import gg.tame.conduit.protocol.semantic.PluginMessagePacket;
import gg.tame.conduit.protocol.translate.Protocol765To766Translator;
import gg.tame.conduit.protocol.translate.TranslationException;
import java.util.Arrays;

/** Phase 14 — Protocol translation architecture and 765↔766 proof. */
public final class Phase14ProtocolTranslationTests {
  private Phase14ProtocolTranslationTests() {}

  public static void run() throws Exception {
    catalogAndCompatibility();
    semanticCodecRoundTrip();
    translateFinishConfiguration();
    translatePluginMessage();
    translateKeepAlive();
    unknownPacketFails();
    joinGameUnsupported();
    identityUnchanged();
    System.out.println("Phase14ProtocolTranslationTests passed.");
  }

  private static void catalogAndCompatibility() {
    require(ProtocolDefinition.hasCodec(765) && ProtocolDefinition.hasCodec(766) && ProtocolDefinition.hasCodec(776), "codecs");
    require(ProtocolCatalog.withCodecs().stream().anyMatch(v -> v.number() == 766), "catalog 766");
    require(ProtocolCompatibility.between(765, 765) == TranslationSupport.DIRECT, "direct 765");
    require(ProtocolCompatibility.between(766, 766) == TranslationSupport.DIRECT, "direct 766");
    require(ProtocolCompatibility.between(765, 766) == TranslationSupport.TRANSLATED, "translated");
    require(ProtocolCompatibility.between(765, 776) == AllTests.viaCarried(), "765-776 is carried by Via or by nothing");
    require(ProtocolDefinition.forVersion(766).knownPacks(), "766 known packs");
    require(!ProtocolDefinition.forVersion(765).knownPacks(), "765 no known packs");
    require(ProtocolDefinition.forVersion(766).capabilities().cookiePackets(), "766 cookies");
  }

  private static void semanticCodecRoundTrip() throws Exception {
    ProtocolDefinition v765 = ProtocolDefinition.forVersion(765);
    SemanticCodec codec = new SemanticCodec(v765, 4096);
    EmptyPacket finish = new EmptyPacket(PacketKind.CONFIGURATION_FINISH, ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT);
    byte[] encoded = codec.encode(finish);
    require(v765.is(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PlayPackets.packetId(encoded), PacketKind.CONFIGURATION_FINISH), "finish id");
    require(codec.decode(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, encoded) instanceof EmptyPacket, "decode empty");
  }

  private static void translateFinishConfiguration() throws Exception {
    ProtocolTranslator pair = AllTests.nativePair(765, 766);
    ProtocolDefinition v765 = ProtocolDefinition.forVersion(765);
    ProtocolDefinition v766 = ProtocolDefinition.forVersion(766);
    byte[] from766 = PlayPackets.withId(v766.id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_FINISH), new byte[0]);
    byte[] toClient = pair.backendToClient(ConnectionState.CONFIGURATION, from766);
    require(PlayPackets.packetId(toClient) == v765.id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_FINISH), "finish to client");
    byte[] from765 = PlayPackets.withId(v765.id(ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_FINISH), new byte[0]);
    byte[] toBackend = pair.clientToBackend(ConnectionState.CONFIGURATION, from765);
    require(PlayPackets.packetId(toBackend) == v766.id(ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_FINISH), "finish to backend");
  }

  private static void translatePluginMessage() throws Exception {
    ProtocolTranslator pair = AllTests.nativePair(765, 766);
    byte[] brand766 = new PluginMessage("minecraft:brand", PluginMessage.brandPayload("vanilla")).encode(
        ProtocolDefinition.forVersion(766).id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_PLUGIN_MESSAGE));
    byte[] toClient = pair.backendToClient(ConnectionState.CONFIGURATION, brand766);
    require(PlayPackets.packetId(toClient) == 0x00, "765 config plugin message id");
    var decoded = new SemanticCodec(ProtocolDefinition.forVersion(765), 4096)
        .decode(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, toClient);
    require(decoded instanceof PluginMessagePacket pm && pm.channel().equals("minecraft:brand"), "brand channel");
  }

  private static void translateKeepAlive() throws Exception {
    ProtocolTranslator pair = AllTests.nativePair(765, 766);
    KeepAlivePacket keep = new KeepAlivePacket(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, 42L);
    byte[] source = new SemanticCodec(ProtocolDefinition.forVersion(766), 64).encode(keep);
    byte[] target = pair.backendToClient(ConnectionState.CONFIGURATION, source);
    KeepAlivePacket decoded = (KeepAlivePacket) new SemanticCodec(ProtocolDefinition.forVersion(765), 64)
        .decode(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, target);
    require(decoded.id() == 42L, "keepalive id preserved");
  }

  private static void unknownPacketFails() {
    ProtocolTranslator translator = AllTests.nativePair(765, 766);
    try {
      translator.clientToBackend(ConnectionState.PLAY, new byte[] {0x7F, 0x01, 0x02});
      throw new AssertionError("unknown accepted");
    } catch (TranslationException expected) {
      require(expected.getMessage().contains("unsupported"), "message");
    }
  }

  private static void joinGameUnsupported() throws Exception {
    ProtocolTranslator translator = AllTests.nativePair(765, 766);
    byte[] join = PlayPackets.withId(
        ProtocolDefinition.forVersion(766).id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN),
        new byte[] {0, 0, 0, 1});
    try {
      translator.backendToClient(ConnectionState.PLAY, join);
      throw new AssertionError("join game accepted");
    } catch (TranslationException expected) {
      require(expected.getMessage().contains("PLAY_LOGIN") || expected.getMessage().contains("PARTIAL"), "join partial");
    }
  }

  private static void identityUnchanged() {
    byte[] packet = {9, 8, 7};
    require(IdentityTranslator.INSTANCE.backendToClient(ConnectionState.PLAY, packet) == packet, "identity ref");
    require(Arrays.equals(packet, Translators.forPair(766, 766).clientToBackend(ConnectionState.PLAY, packet)), "direct pair");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
