// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.protocol.CompatibilityRegistry;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolCompatibility;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.TranslationSupport;
import gg.tame.conduit.protocol.TranslatorRegistry;
import gg.tame.conduit.protocol.Translators;
import gg.tame.conduit.protocol.ValidationStatus;
import gg.tame.conduit.protocol.entity.MetadataCodec;
import gg.tame.conduit.protocol.inventory.ContainerCodec;
import gg.tame.conduit.protocol.item.ItemCodec;
import gg.tame.conduit.protocol.item.SemanticItem;
import gg.tame.conduit.protocol.translate.Protocol393To404Translator;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

/**
 * Protocol 404 (1.13.2) codec + 393↔404 Slot-delta translation.
 *
 * <p>Packet ids are unchanged from 393. The gameplay-critical delta is Slot:
 * short id on 393, present+VarInt on 404. These tests prove both ItemCodec
 * boundaries and that the registered translator rematerialises inventory,
 * equipment, creative, click and metadata packets without inventing a
 * pair-specific architecture.
 */
public final class Phase20_393_404_TranslationTests {
  private Phase20_393_404_TranslationTests() {}

  public static void run() throws Exception {
    codecAndCompatibility();
    itemSlotRoundTrip();
    containerAndClickRematerialise();
    equipmentAndCreative();
    metadataSlotOnly();
    translatorEndToEnd();
    System.out.println("Phase20_393_404_TranslationTests passed.");
  }

  private static void codecAndCompatibility() {
    require(ProtocolDefinition.hasCodec(404), "404 must have a derived codec");
    require(ProtocolDefinition.forVersion(404).defines(
            ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN),
        "404 inherits Join Game");
    require(ProtocolCompatibility.between(404, 404) == TranslationSupport.DIRECT, "404 DIRECT");
    require(ProtocolCompatibility.between(393, 404) == TranslationSupport.TRANSLATED, "393→404");
    require(ProtocolCompatibility.between(404, 393) == TranslationSupport.TRANSLATED, "404→393");
    require(TranslatorRegistry.has(393, 404) && TranslatorRegistry.has(404, 393), "pairs registered");
    require(CompatibilityRegistry.resolve(393, 404).validation() == ValidationStatus.TRANSLATED_PARTIAL
            || CompatibilityRegistry.resolve(393, 404).validation() == ValidationStatus.TRANSLATED_VERIFIED,
        "393→404 validation axis set");
  }

  private static void itemSlotRoundTrip() throws Exception {
    SemanticItem stone = SemanticItem.of("minecraft:stone", 32);
    byte[] as393 = encodeSlot(393, stone);
    byte[] as404 = encodeSlot(404, stone);
    require(as393[0] != 0 || as393[1] != 0, "393 short id is non-zero");
    require(as404[0] == 1, "404 present flag");
    require(decodeSlot(393, as393).identifier().equals("minecraft:stone"), "393 read");
    require(decodeSlot(404, as404).identifier().equals("minecraft:stone"), "404 read");
    require(decodeSlot(404, translateSlot(393, 404, as393)).count() == 32, "393→404");
    require(decodeSlot(393, translateSlot(404, 393, as404)).count() == 32, "404→393");
    require(decodeSlot(393, translateSlot(404, 393, encodeSlot(404, SemanticItem.EMPTY))).isEmpty(),
        "empty survives 404→393");
  }

  private static void containerAndClickRematerialise() throws Exception {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(body);
    out.writeByte(0);
    out.writeShort(2);
    ItemCodec.write(393, out, SemanticItem.of("minecraft:dirt", 1));
    ItemCodec.write(393, out, SemanticItem.EMPTY);
    byte[] to404 = ContainerCodec.containerContent(393, 404, body.toByteArray(), 0);
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(to404))) {
      require(in.readUnsignedByte() == 0, "window id");
      require(in.readShort() == 2, "legacy count framing on 404");
      require(ItemCodec.read(404, in).identifier().equals("minecraft:dirt"), "slot 0");
      require(ItemCodec.read(404, in).isEmpty(), "slot 1 empty");
    }

    ByteArrayOutputStream click = new ByteArrayOutputStream();
    DataOutputStream cout = new DataOutputStream(click);
    cout.writeByte(1);
    cout.writeShort(36);
    cout.writeByte(0);
    cout.writeShort(9);
    MinecraftOutput.varInt(cout, 0);
    ItemCodec.write(393, cout, SemanticItem.of("minecraft:oak_planks", 4));
    var decoded = ContainerCodec.readClick(393, click.toByteArray());
    require(decoded.actionNumber() == 9, "action preserved");
    byte[] click404 = ContainerCodec.writeClick(404, decoded, 0, decoded.actionNumber());
    var back = ContainerCodec.readClick(404, click404);
    require(back.actionNumber() == 9 && back.carried().count() == 4, "click 393→404");
  }

  private static void equipmentAndCreative() throws Exception {
    var change = new ContainerCodec.Equipment(7, 0, SemanticItem.of("minecraft:iron_sword", 1));
    byte[] eq404 = ContainerCodec.writeEquipment(404, change);
    var read = ContainerCodec.readEquipment(404, eq404);
    require(read.size() == 1 && read.get(0).item().identifier().equals("minecraft:iron_sword"),
        "equipment 404");

    ByteArrayOutputStream creative = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(creative);
    out.writeShort(36);
    ItemCodec.write(404, out, SemanticItem.of("minecraft:torch", 16));
    byte[] to393 = ContainerCodec.creativeSlot(404, 393, creative.toByteArray());
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(to393))) {
      require(in.readShort() == 36, "creative slot");
      require(ItemCodec.read(393, in).count() == 16, "creative item");
    }
  }

  private static void metadataSlotOnly() throws Exception {
    // Dropped-item style: index 6 Slot with short id 473 (arrow) on 393.
    byte[] captured = {
        (byte) 0x06, (byte) 0x06, (byte) 0x01, (byte) 0xd9,
        (byte) 0x01, (byte) 0x00,
        (byte) 0xff
    };
    byte[] as404 = MetadataCodec.translate(
        ProtocolDefinition.forVersion(393), ProtocolDefinition.forVersion(404), captured, false);
    require(as404 != null, "metadata translates");
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(as404))) {
      require(in.readUnsignedByte() == 6, "index unchanged");
      require(MinecraftInput.varInt(in) == 6, "Slot type unchanged");
      SemanticItem item = ItemCodec.read(404, in);
      require(item.identifier().equals("minecraft:arrow") && item.count() == 1, "arrow");
      require(in.readUnsignedByte() == 0xff, "terminator");
    }

    // Full Set Entity Metadata body: entityId + metadata in 404 Slot form.
    ByteArrayOutputStream meta404 = new ByteArrayOutputStream();
    DataOutputStream mout = new DataOutputStream(meta404);
    mout.writeByte(6);
    MinecraftOutput.varInt(mout, 6);
    ItemCodec.write(404, mout, SemanticItem.of("minecraft:arrow", 1));
    mout.writeByte(0xff);
    ByteArrayOutputStream full = new ByteArrayOutputStream();
    DataOutputStream fout = new DataOutputStream(full);
    MinecraftOutput.varInt(fout, 222);
    fout.write(meta404.toByteArray());
    int metaId = ProtocolDefinition.forVersion(404)
        .id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SET_ENTITY_METADATA);
    byte[] packet404 = PlayPackets.withId(metaId, full.toByteArray());
    byte[] packet393 = Translators.forPair(393, 404).backendToClient(ConnectionState.PLAY, packet404);
    require(packet393 != null, "metadata packet through translator");
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(packet393)))) {
      require(MinecraftInput.varInt(in) == 222, "entity id preserved");
      require(in.readUnsignedByte() == 6, "index");
      require(MinecraftInput.varInt(in) == 6, "Slot type");
      require(ItemCodec.read(393, in).identifier().equals("minecraft:arrow"), "arrow as short-id slot");
    }
  }

  private static void translatorEndToEnd() throws Exception {
    var forward = Protocol393To404Translator.client393();
    var reverse = Protocol393To404Translator.client404();
    require(forward.sourceProtocol() == 393 && forward.targetProtocol() == 404, "forward pair");
    require(reverse.sourceProtocol() == 404 && reverse.targetProtocol() == 393, "reverse pair");

    ByteArrayOutputStream slotBody = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(slotBody);
    out.writeByte(0);           // window
    out.writeShort(36);         // slot
    ItemCodec.write(404, out, SemanticItem.of("minecraft:cobblestone", 8));
    int id = ProtocolDefinition.forVersion(404)
        .id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SET_CONTAINER_SLOT);
    byte[] packet404 = PlayPackets.withId(id, slotBody.toByteArray());
    // Client 393, backend 404: backend→client rematerialises Slot to short-id form.
    byte[] packet393 = Translators.forPair(393, 404).backendToClient(ConnectionState.PLAY, packet404);
    require(packet393 != null, "translator emits");
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(packet393)))) {
      in.readByte();
      in.readShort();
      require(ItemCodec.read(393, in).identifier().equals("minecraft:cobblestone"), "slot through translator");
    }

    // KeepAlive must opaque-forward with identical body (client 393 → backend 404).
    ByteArrayOutputStream keep = new ByteArrayOutputStream();
    DataOutputStream kout = new DataOutputStream(keep);
    kout.writeLong(0x1122334455667788L);
    int keepId = ProtocolDefinition.forVersion(393)
        .id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_KEEP_ALIVE);
    byte[] keep393 = PlayPackets.withId(keepId, keep.toByteArray());
    byte[] keep404 = Translators.forPair(393, 404).clientToBackend(ConnectionState.PLAY, keep393);
    require(PlayPackets.packetId(keep404) == ProtocolDefinition.forVersion(404)
            .id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_KEEP_ALIVE),
        "keepalive id on 404");
    require(java.util.Arrays.equals(PlayPackets.body(keep393), PlayPackets.body(keep404)),
        "keepalive body unchanged");
  }

  private static byte[] encodeSlot(int protocol, SemanticItem item) throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    ItemCodec.write(protocol, new DataOutputStream(buffer), item);
    return buffer.toByteArray();
  }

  private static SemanticItem decodeSlot(int protocol, byte[] bytes) throws Exception {
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
      return ItemCodec.read(protocol, in);
    }
  }

  private static byte[] translateSlot(int from, int to, byte[] bytes) throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
      ItemCodec.translate(from, to, in, new DataOutputStream(buffer));
    }
    return buffer.toByteArray();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
