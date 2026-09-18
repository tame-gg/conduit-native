// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.RecipeListRepair;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Covers the 1.13 Declare Recipes repair. The malformed shape asserted here is the one a real
 * 1.13 client rejected: an empty result slot written with a trailing count and tag.
 *
 * <p>Pointing {@code -Dconduit.recipe.capture=<file>} at a clientbound dump replays the repair
 * against real captured bytes instead of the synthetic ones.
 */
public final class RecipeListRepairTests {
  private RecipeListRepairTests() {}

  public static void main(String[] args) throws Exception { run(); }

  public static void run() throws Exception {
    ProtocolDefinition client = ProtocolDefinition.forVersion(393);
    int id = client.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DECLARE_RECIPES);

    byte[] wellFormed = recipeList(id, false);
    require(RecipeListRepair.apply(client, wellFormed) == wellFormed,
        "a correct recipe list is forwarded untouched, by identity");

    byte[] malformed = recipeList(id, true);
    byte[] repaired = RecipeListRepair.apply(client, malformed);
    require(repaired != malformed, "a malformed recipe list is replaced");
    require(repaired.length < malformed.length, "the unrepresentable recipe is gone");
    require(RecipeListRepair.apply(client, repaired) == repaired,
        "the repaired list parses strictly, so it survives a second pass unchanged");
    require(countOf(repaired, id) == 2, "the two representable recipes are kept and the count corrected");

    // A packet that is not Declare Recipes is never touched, whatever it contains.
    byte[] other = new byte[] {0x21, 0x00, 0x00};
    require(RecipeListRepair.apply(client, other) == other, "unrelated packets pass through");

    // 1.13.2 writes a slot as a present flag and a VarInt id. Read in 1.13's short-id layout, a real
    // 1.13.2 server's recipe list fit neither reading and every 1.13.2 client was sent an empty one.
    ProtocolDefinition v1132 = ProtocolDefinition.forVersion(404);
    int id1132 = v1132.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DECLARE_RECIPES);
    byte[] wellFormed1132 = recipeList1132(id1132);
    require(RecipeListRepair.apply(v1132, wellFormed1132) == wellFormed1132,
        "a correct 1.13.2 recipe list is forwarded untouched, by identity");

    // A client whose recipe layout this does not cover is never touched.
    require(!RecipeListRepair.handles(477), "1.14 is out of scope for the 1.13 recipe layout");
    require(!RecipeListRepair.handles(765), "1.20.4 is out of scope for the 1.13 recipe layout");

    String capture = System.getProperty("conduit.recipe.capture");
    if (capture != null) replayCapture(client, id, Path.of(capture));

    System.out.println("RecipeListRepairTests passed");
  }

  /** Two ordinary recipes with a third between them whose result is empty. */
  private static byte[] recipeList(int packetId, boolean brokenEmptySlot) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream sink = new DataOutputStream(out);
    MinecraftOutput.varInt(sink, packetId);
    MinecraftOutput.varInt(sink, 3);
    shaped(sink, "conduit:first", 1, false);
    shaped(sink, "conduit:unrepresentable", 2, brokenEmptySlot);
    shaped(sink, "conduit:second", 3, false);
    return out.toByteArray();
  }

  private static void shaped(DataOutputStream sink, String name, int ingredient, boolean emptyResult)
      throws IOException {
    MinecraftOutput.string(sink, name);
    MinecraftOutput.string(sink, "crafting_shaped");
    MinecraftOutput.varInt(sink, 1); // width
    MinecraftOutput.varInt(sink, 1); // height
    MinecraftOutput.string(sink, ""); // group
    MinecraftOutput.varInt(sink, 1); // one option for the single ingredient
    slot(sink, ingredient, false);
    if (emptyResult) slot(sink, -1, true);
    else slot(sink, ingredient + 100, false);
  }

  /**
   * Writes a 1.13 slot. {@code trailing} writes the count and tag after an empty id, which is what
   * the 1.13 client cannot read.
   */
  private static void slot(DataOutputStream sink, int id, boolean trailing) throws IOException {
    sink.writeShort(id);
    if (id == -1 && !trailing) return;
    sink.writeByte(1);
    sink.writeByte(0); // TAG_End: no tag
  }

  /** A shaped recipe, a smelting recipe with an empty-flagged ingredient option, in 1.13.2's slot layout. */
  private static byte[] recipeList1132(int packetId) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream sink = new DataOutputStream(out);
    MinecraftOutput.varInt(sink, packetId);
    MinecraftOutput.varInt(sink, 2);
    MinecraftOutput.string(sink, "minecraft:oak_planks");
    MinecraftOutput.string(sink, "crafting_shaped");
    MinecraftOutput.varInt(sink, 1);
    MinecraftOutput.varInt(sink, 1);
    MinecraftOutput.string(sink, "planks");
    MinecraftOutput.varInt(sink, 1);
    slot1132(sink, 32);
    slot1132(sink, 13);
    MinecraftOutput.string(sink, "minecraft:glass");
    MinecraftOutput.string(sink, "smelting");
    MinecraftOutput.string(sink, "");
    MinecraftOutput.varInt(sink, 2);
    slot1132(sink, 26);
    slot1132(sink, 300); // a two-byte VarInt id
    slot1132(sink, 64);
    sink.writeFloat(0.1f);
    MinecraftOutput.varInt(sink, 200);
    return out.toByteArray();
  }

  private static void slot1132(DataOutputStream sink, int id) throws IOException {
    sink.writeBoolean(true);
    MinecraftOutput.varInt(sink, id);
    sink.writeByte(1);
    sink.writeByte(0); // TAG_End: no tag
  }

  private static int countOf(byte[] packet, int packetId) throws IOException {
    java.io.DataInputStream input =
        new java.io.DataInputStream(new java.io.ByteArrayInputStream(packet));
    require(gg.tame.conduit.protocol.MinecraftInput.varInt(input) == packetId, "packet id preserved");
    return gg.tame.conduit.protocol.MinecraftInput.varInt(input);
  }

  /** Replays the repair over every packet in a clientbound dump (4-byte length, then body). */
  private static void replayCapture(ProtocolDefinition client, int id, Path file) throws IOException {
    byte[] dump = Files.readAllBytes(file);
    int offset = 0;
    int seen = 0;
    while (offset + 4 <= dump.length) {
      int length = ((dump[offset] & 0xff) << 24) | ((dump[offset + 1] & 0xff) << 16)
          | ((dump[offset + 2] & 0xff) << 8) | (dump[offset + 3] & 0xff);
      offset += 4;
      if (length < 0 || offset + length > dump.length) break;
      byte[] packet = java.util.Arrays.copyOfRange(dump, offset, offset + length);
      offset += length;
      byte[] repaired = RecipeListRepair.apply(client, packet);
      if (repaired == packet) continue;
      seen++;
      require(RecipeListRepair.apply(client, repaired) == repaired,
          "captured recipe list parses strictly after repair");
      System.out.println("  capture: repaired " + packet.length + " bytes to " + repaired.length
          + ", recipes " + countOf(packet, id) + " -> " + countOf(repaired, id));
    }
    require(seen > 0, "the capture contained a recipe list needing repair");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
