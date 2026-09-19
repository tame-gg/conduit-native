// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.ProtocolTranslator;
import gg.tame.conduit.protocol.chunk.BlockStateMaps;
import gg.tame.conduit.protocol.particle.ParticleRegistries;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

/**
 * Particles across 393 ↔ 765.
 *
 * <p>Particles were dropped by this pair on two counts: the registry index means
 * a different particle on each side, and the packet's own layout changed (the id
 * widens to a VarInt, the position to doubles). Four of them also carry a payload
 * naming the sender's block or item registry. These tests pin all three.
 */
public final class ParticleTranslationTests {
  private ParticleTranslationTests() {}

  public static void run() throws Exception {
    registriesAreTheRealOnes();
    idsAreNotInterchangeable();
    plainParticleSurvivesBothWays();
    blockPayloadIsTranslatedNotCopied();
    dustPayloadSurvives();
    aParticleOnlyOneSideHasIsDropped();
    malformedBodiesFailClosed();
    System.out.println("ParticleTranslationTests passed.");
  }

  /** Spot-checks the generated tables against Mojang's registries. */
  private static void registriesAreTheRealOnes() {
    require(ParticleRegistries.size(393) == 50, "1.13 has 50 particle types");
    require(ParticleRegistries.size(765) == 101, "1.20.4 has 101 particle types");
    require(ParticleRegistries.name(393, 0).orElseThrow()
        .equals("minecraft:ambient_entity_effect"), "1.13 particle 0 is ambient_entity_effect");
    // 1.13's last three are appended rather than alphabetical, which is what a real
    // registration order looks like and a sorted key set does not.
    require(ParticleRegistries.name(393, 49).orElseThrow().equals("minecraft:dolphin"),
        "1.13 particle 49 is dolphin");
    require(ParticleRegistries.translates(393, 765) && ParticleRegistries.translates(765, 393),
        "the pair translates particles in both directions");
  }

  /** The point of the table: the same index means different particles on the two sides. */
  private static void idsAreNotInterchangeable() {
    require(ParticleRegistries.translate(393, 765, 49).orElseThrow()
        == ParticleRegistries.id(765, "minecraft:dolphin").orElseThrow(),
        "1.13 dolphin resolves to 1.20.4 dolphin, by name");
    require(ParticleRegistries.translate(393, 765, 49).orElseThrow() != 49,
        "and not to 1.20.4 id 49");
  }

  /** A particle with no payload arrives as the same particle, with its fields intact. */
  private static void plainParticleSurvivesBothWays() throws Exception {
    int flame393 = ParticleRegistries.id(393, "minecraft:flame").orElseThrow();
    byte[] out = translate(393, 765, particle393(flame393, true, 1.5f, 2.5f, 3.5f, 9, new byte[0]));
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(out))) {
      require(MinecraftInput.varInt(in)
          == ParticleRegistries.id(765, "minecraft:flame").orElseThrow(), "flame reaches 1.20.4");
      require(in.readBoolean(), "long distance survives");
      require(in.readDouble() == 1.5 && in.readDouble() == 2.5 && in.readDouble() == 3.5,
          "the position widens to doubles without changing");
      in.readFloat(); in.readFloat(); in.readFloat(); in.readFloat();
      require(in.readInt() == 9, "the count survives");
      require(in.read() == -1, "a flame carries no payload");
    }
  }

  /**
   * A block particle's payload is a block state, and block state ids are no more
   * interchangeable than particle ids, so it has to be translated too.
   */
  private static void blockPayloadIsTranslatedNotCopied() throws Exception {
    int block393 = ParticleRegistries.id(393, "minecraft:block").orElseThrow();
    int state393 = 100;                                    // an ordinary 1.13 block state
    int expected = BlockStateMaps.translate(393, 765, state393);
    require(expected >= 0, "the fixture block state maps to 1.20.4");

    ByteArrayOutputStream payload = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(payload)) {
      MinecraftOutput.varInt(out, state393);
    }
    byte[] out = translate(393, 765,
        particle393(block393, false, 0, 0, 0, 1, payload.toByteArray()));
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(out))) {
      MinecraftInput.varInt(in);
      in.readBoolean();
      in.readDouble(); in.readDouble(); in.readDouble();
      in.readFloat(); in.readFloat(); in.readFloat(); in.readFloat();
      in.readInt();
      int state = MinecraftInput.varInt(in);
      require(state == expected, "the block state is translated, not copied");
      require(in.read() == -1, "and nothing follows it");
    }
  }

  /** A dust particle's four floats mean the same on both sides and are carried through. */
  private static void dustPayloadSurvives() throws Exception {
    int dust393 = ParticleRegistries.id(393, "minecraft:dust").orElseThrow();
    ByteArrayOutputStream payload = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(payload)) {
      out.writeFloat(0.25f); out.writeFloat(0.5f); out.writeFloat(0.75f); out.writeFloat(2f);
    }
    byte[] out = translate(393, 765,
        particle393(dust393, false, 0, 0, 0, 1, payload.toByteArray()));
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(out))) {
      MinecraftInput.varInt(in);
      in.readBoolean();
      in.readDouble(); in.readDouble(); in.readDouble();
      in.readFloat(); in.readFloat(); in.readFloat(); in.readFloat();
      in.readInt();
      require(in.readFloat() == 0.25f && in.readFloat() == 0.5f && in.readFloat() == 0.75f,
          "the dust colour survives");
      require(in.readFloat() == 2f, "and so does its scale");
      require(in.read() == -1, "and nothing follows it");
    }
  }

  /**
   * 1.13's barrier particle has no 1.20.4 counterpart and is dropped.
   *
   * <p>1.18 replaced it with block_marker, which takes a block state as its
   * payload rather than standing alone, so it is a different particle and not a
   * rename this table could make.
   */
  private static void aParticleOnlyOneSideHasIsDropped() throws Exception {
    int barrier = ParticleRegistries.id(393, "minecraft:barrier").orElseThrow();
    require(ParticleRegistries.translate(393, 765, barrier).isEmpty(),
        "barrier has no 1.20.4 counterpart");
    ProtocolTranslator translator = AllTests.nativePair(765, 393);
    byte[] packet = PlayPackets.withId(ProtocolDefinition.forVersion(393).id(ConnectionState.PLAY,
        PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_WORLD_PARTICLES),
        particle393(barrier, false, 0, 0, 0, 1, new byte[0]));
    require(translator.backendToClient(ConnectionState.PLAY, packet) == null,
        "and the packet is dropped rather than drawn as something else");
  }

  /** A body that is not a particle is dropped, never forwarded as bytes. */
  private static void malformedBodiesFailClosed() {
    require(gg.tame.conduit.protocol.particle.ParticleCodec.translate(393, 765, new byte[0]) == null,
        "an empty body is not a particle");
    require(gg.tame.conduit.protocol.particle.ParticleCodec.translate(393, 765,
        new byte[] {0, 0, 0, 1, 1}) == null, "a truncated body is not a particle");
    int flame = ParticleRegistries.id(393, "minecraft:flame").orElseThrow();
    byte[] valid = particle393(flame, false, 0, 0, 0, 1, new byte[0]);
    byte[] trailing = java.util.Arrays.copyOf(valid, valid.length + 1);
    require(gg.tame.conduit.protocol.particle.ParticleCodec.translate(393, 765, trailing) == null,
        "trailing bytes mean it is not a particle");
  }

  /** A clientbound Particle a 1.13 backend sends, as the {@code to} client receives it. */
  private static byte[] translate(int from, int to, byte[] body) throws Exception {
    ProtocolTranslator translator = AllTests.nativePair(to, from);
    byte[] packet = PlayPackets.withId(ProtocolDefinition.forVersion(from)
        .id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT,
            PacketKind.PLAY_WORLD_PARTICLES), body);
    byte[] out = translator.backendToClient(ConnectionState.PLAY, packet);
    require(out != null, "the " + from + " -> " + to + " translator dropped the particle");
    return PlayPackets.body(out);
  }

  private static byte[] particle393(int id, boolean longDistance, float x, float y, float z,
                                    int count, byte[] payload) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      out.writeInt(id);
      out.writeBoolean(longDistance);
      out.writeFloat(x); out.writeFloat(y); out.writeFloat(z);
      out.writeFloat(0f); out.writeFloat(0f); out.writeFloat(0f);
      out.writeFloat(0f);
      out.writeInt(count);
      out.write(payload);
    } catch (java.io.IOException impossible) {
      throw new AssertionError(impossible);
    }
    return bytes.toByteArray();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
