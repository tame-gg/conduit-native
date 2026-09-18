// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.protocol.CompatibilityRegistry;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolCompatibility;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.ProtocolEras;
import gg.tame.conduit.protocol.TranslationSupport;
import gg.tame.conduit.protocol.TranslatorRegistry;
import gg.tame.conduit.protocol.chunk.ChunkCodec393;
import gg.tame.conduit.protocol.chunk.ChunkCodec477;
import gg.tame.conduit.protocol.chunk.SemanticChunk;
import gg.tame.conduit.protocol.chunk.SemanticChunkSection;
import gg.tame.conduit.protocol.codec.BlockPlaceCodec;
import gg.tame.conduit.protocol.codec.BlockPositionCodec;
import gg.tame.conduit.protocol.codec.JoinGameCodec;
import gg.tame.conduit.protocol.inventory.ContainerCodec;
import gg.tame.conduit.protocol.semantic.JoinGamePacket;
import gg.tame.conduit.protocol.translate.Protocol404To477Translator;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.List;

/** Protocol 477 codec + 404↔477 core-delta translation unit tests. */
public final class Phase21_404_477_TranslationTests {
  private Phase21_404_477_TranslationTests() {}

  public static void run() throws Exception {
    codecAndCompatibility();
    joinGameRoundTrip();
    openWindowRoundTrip();
    positionAndBlockPlace();
    chunkHeightmapsAndLight();
    translatorEndToEnd();
    System.out.println("Phase21_404_477_TranslationTests passed.");
  }

  private static void codecAndCompatibility() {
    require(ProtocolDefinition.hasCodec(477), "477 must have a derived codec");
    require(ProtocolDefinition.forVersion(477).defines(
            ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_OPEN_WINDOW),
        "477 maps Open Window");
    require(ProtocolDefinition.forVersion(477).id(
            ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_OPEN_WINDOW) == 0x2E,
        "477 Open Window id 0x2E");
    require(ProtocolDefinition.forVersion(477).id(
            ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_BLOCK_PLACE) == 0x2C,
        "477 Block Place id 0x2C");
    require(!ProtocolDefinition.forVersion(477).defines(
            ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_USE_BED),
        "477 removed Use Bed");
    require(ProtocolCompatibility.between(477, 477) == TranslationSupport.DIRECT, "477 DIRECT");
    require(ProtocolCompatibility.between(404, 477) == TranslationSupport.TRANSLATED, "404→477");
    require(ProtocolCompatibility.between(477, 404) == TranslationSupport.TRANSLATED, "477→404");
    require(TranslatorRegistry.has(404, 477) && TranslatorRegistry.has(477, 404), "pairs registered");
    require(ProtocolEras.joinGame114(477) && !ProtocolEras.joinGameRegistry(477), "477 mid-era join");
    require(CompatibilityRegistry.resolve(404, 477).support() == TranslationSupport.TRANSLATED, "compat entry");
  }

  private static void joinGameRoundTrip() throws Exception {
    ProtocolDefinition v404 = ProtocolDefinition.forVersion(404);
    ProtocolDefinition v477 = ProtocolDefinition.forVersion(477);
    JoinGamePacket join = new JoinGamePacket(
        PacketDirection.SERVER_TO_CLIENT, 7, false, 1, -1, 0,
        "minecraft:overworld", "minecraft:overworld", List.of("minecraft:overworld"),
        0L, 20, 10, 10, false, true, false, false, (byte) 2, "default", false, 0);
    byte[] as404 = JoinGameCodec.encode(v404, join);
    byte[] as477 = JoinGameCodec.encode(v477, join);
    JoinGamePacket back404 = JoinGameCodec.decode(v404, as404);
    JoinGamePacket back477 = JoinGameCodec.decode(v477, as477);
    require(back404.difficulty() == 2, "404 keeps difficulty");
    require(back477.viewDistance() == 10, "477 keeps viewDistance");
    require(back477.dimensionId() == 0, "477 dimension");
    // Cross encode through semantic decode.
    JoinGamePacket from404 = JoinGameCodec.decode(v404, as404);
    byte[] to477 = JoinGameCodec.encode(v477, from404);
    JoinGamePacket cross = JoinGameCodec.decode(v477, to477);
    require(cross.entityId() == 7 && cross.viewDistance() >= 2, "404→477 join");
  }

  private static void openWindowRoundTrip() throws Exception {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(body);
    out.writeByte(1);
    gg.tame.conduit.protocol.MinecraftOutput.string(out, "minecraft:chest");
    gg.tame.conduit.protocol.MinecraftOutput.string(out, "{\"text\":\"Box\"}");
    out.writeByte(27);
    byte[] to477 = ContainerCodec.openWindow(404, 477, body.toByteArray());
    require(to477 != null && to477.length > 2, "404→477 open window");
    byte[] back = ContainerCodec.openWindow(477, 404, to477);
    require(back != null, "477→404 open window");
  }

  private static void positionAndBlockPlace() throws Exception {
    ProtocolDefinition v404 = ProtocolDefinition.forVersion(404);
    ProtocolDefinition v477 = ProtocolDefinition.forVersion(477);
    var pos = new BlockPositionCodec.BlockPosition(-38, 70, 28);
    long packed404 = BlockPositionCodec.pack(v404, pos);
    long packed477 = BlockPositionCodec.pack(v477, pos);
    require(packed404 != packed477, "position packing differs");
    var round = BlockPositionCodec.unpack(v477, BlockPositionCodec.translate(v404, v477, packed404));
    require(round.x() == -38 && round.y() == 70 && round.z() == 28, "position rematerialise");

    ByteArrayOutputStream place404 = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(place404);
    out.writeLong(packed404);
    gg.tame.conduit.protocol.MinecraftOutput.varInt(out, 1);
    gg.tame.conduit.protocol.MinecraftOutput.varInt(out, 0);
    out.writeFloat(0.5f); out.writeFloat(1f); out.writeFloat(0.5f);
    byte[] place477 = BlockPlaceCodec.translate(place404.toByteArray(), v404, v477);
    require(place477.length > place404.size(), "477 adds insideBlock");
    byte[] back = BlockPlaceCodec.translate(place477, v477, v404);
    require(back.length == place404.size(), "place round-trip length");
  }

  private static void chunkHeightmapsAndLight() throws Exception {
    int[] states = new int[4096];
    java.util.Arrays.fill(states, 1); // stone
    byte[] light = new byte[2048];
    java.util.Arrays.fill(light, (byte) 0xFF);
    var section = new SemanticChunkSection(4096, states, new int[0], light, light);
    var chunk = new SemanticChunk(2, -3, true, List.of(new SemanticChunk.SectionSlot(4, section)),
        new int[256], List.of(), new byte[0]);
    byte[] as404 = ChunkCodec393.encodeLegacy(chunk);
    byte[] as477 = ChunkCodec477.encode(chunk);
    require(as477.length != as404.length, "477 wire differs (heightmaps)");
    var decoded477 = ChunkCodec477.decode(as477);
    require(decoded477.chunkX() == 2 && decoded477.chunkZ() == -3, "477 chunk coords");
    byte[] lightBody = ChunkCodec477.encodeUpdateLight(chunk);
    require(lightBody.length > 8, "update light synthesised");
  }

  private static void translatorEndToEnd() throws Exception {
    var forward = Protocol404To477Translator.client404();
    var reverse = Protocol404To477Translator.client477();
    ProtocolDefinition v404 = ProtocolDefinition.forVersion(404);
    JoinGamePacket join = new JoinGamePacket(
        PacketDirection.SERVER_TO_CLIENT, 1, false, 0, -1, 0,
        "minecraft:overworld", "minecraft:overworld", List.of("minecraft:overworld"),
        0L, 20, 8, 8, false, true, false, false, (byte) 1, "default", false, 0);
    byte[] login404 = JoinGameCodec.encode(v404, join);
    byte[] toClient = reverse.backendToClient(ConnectionState.PLAY, login404);
    // reverse is client477: backend is 404, client is 477 — backendToClient translates 404→477
    require(toClient != null && PlayPackets.packetId(toClient)
            == ProtocolDefinition.forVersion(477).id(
                ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN),
        "404→477 join via translator");
    JoinGamePacket decoded = JoinGameCodec.decode(ProtocolDefinition.forVersion(477), toClient);
    require(decoded.viewDistance() >= 2, "synthesised view distance");

    // Keepalive opaque remap
    byte[] keep = PlayPackets.withId(
        v404.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_KEEP_ALIVE),
        longBody(99L));
    byte[] keep477 = reverse.backendToClient(ConnectionState.PLAY, keep);
    require(PlayPackets.packetId(keep477) == 0x20, "477 keepalive id");
    require(forward != null, "forward translator constructed");
  }

  private static byte[] longBody(long value) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    new DataOutputStream(bytes).writeLong(value);
    return bytes.toByteArray();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
