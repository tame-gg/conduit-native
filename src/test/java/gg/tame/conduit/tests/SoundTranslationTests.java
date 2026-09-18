// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.ProtocolTranslator;
import gg.tame.conduit.protocol.sound.SoundCodec;
import gg.tame.conduit.protocol.sound.SoundRegistries;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

/**
 * Sounds across 393 ↔ 765.
 *
 * <p>Sounds were dropped by this pair until the registry tables existed, because
 * a sound travels as a registry index and the same index is a different sound on
 * each side. These tests pin the two things that makes true: that the tables
 * really are Mojang's registries, and that a sound crossing the pair arrives as
 * the sound that was sent rather than as whatever shares its number.
 */
public final class SoundTranslationTests {
  private SoundTranslationTests() {}

  public static void run() throws Exception {
    registriesAreTheRealOnes();
    idsAreNotInterchangeable();
    soundEffectSurvivesBothWays();
    aSoundOnlyOneSideHasIsNamedRatherThanDropped();
    namedSoundBecomesAnInlineSoundAndBack();
    malformedBodiesFailClosed();
    System.out.println("SoundTranslationTests passed.");
  }

  /**
   * Spot-checks the generated tables against Mojang's registries.
   *
   * <p>These ids are from the official registry dumps the tables are generated
   * from: 1.13's sound registry read out of its own jar, 1.20.4's from its
   * {@code --reports} output. They are asserted so that a regenerated or
   * hand-edited table that quietly shifts cannot pass.
   */
  private static void registriesAreTheRealOnes() {
    require(SoundRegistries.size(393) == 662, "1.13 has 662 sound events");
    require(SoundRegistries.size(765) == 1539, "1.20.4 has 1539 sound events");
    require(SoundRegistries.name(393, 0).orElseThrow().equals("minecraft:ambient.cave"),
        "1.13 sound 0 is ambient.cave");
    require(SoundRegistries.name(765, 0).orElseThrow()
        .equals("minecraft:entity.allay.ambient_with_item"), "1.20.4 sound 0 is the allay");
    require(SoundRegistries.translates(393, 765) && SoundRegistries.translates(765, 393),
        "the pair translates sounds in both directions");
    require(!SoundRegistries.translates(393, 404), "a pair with no generated table does not");
  }

  /**
   * The point of the table: the same index means different sounds on the two sides.
   *
   * <p>If this ever stopped being true the tables would be redundant, and if it is
   * true then forwarding the index — which is what used to happen before sounds
   * were dropped outright — plays the wrong sound.
   */
  private static void idsAreNotInterchangeable() {
    int same = 0;
    for (int id = 0; id < SoundRegistries.size(393); id++) {
      int mapped = SoundRegistries.translate(393, 765, id).orElse(-1);
      if (mapped == id) same++;
    }
    require(same < SoundRegistries.size(393) / 10,
        "1.13 and 1.20.4 sound ids are not interchangeable (" + same + " happen to agree)");
    // ambient.cave is 1.13's id 0 and is not 1.20.4's id 0.
    require(SoundRegistries.translate(393, 765, 0).orElseThrow()
        == SoundRegistries.id(765, "minecraft:ambient.cave").orElseThrow(),
        "1.13 ambient.cave resolves to 1.20.4 ambient.cave, by name");
    require(SoundRegistries.translate(393, 765, 0).orElseThrow() != 0, "and not to 1.20.4 id 0");
  }

  /** A sound both registries have arrives as the same sound, with its fields intact. */
  private static void soundEffectSurvivesBothWays() throws Exception {
    String name = "minecraft:entity.pig.ambient";
    int id393 = SoundRegistries.id(393, name).orElseThrow();
    int id765 = SoundRegistries.id(765, name).orElseThrow();

    byte[] from393 = soundEffect393(id393, 3, 128, 512, 256, 0.75f, 1.25f);
    SoundCodec.Sound toModern = SoundCodec.read(765,
        translate(393, 765, PacketKind.PLAY_SOUND_EFFECT, from393));
    require(toModern.name().equals(name), "the 1.13 sound reaches 1.20.4 by name");
    require(toModern.category() == 3 && toModern.x() == 128 && toModern.y() == 512
        && toModern.z() == 256, "category and position survive");
    require(toModern.volume() == 0.75f && toModern.pitch() == 1.25f, "volume and pitch survive");

    byte[] from765 = soundEffect765(id765 + 1, 3, 128, 512, 256, 0.75f, 1.25f, 99L);
    SoundCodec.Sound toLegacy = SoundCodec.read(393,
        translate(765, 393, PacketKind.PLAY_SOUND_EFFECT, from765));
    require(toLegacy.name().equals(name), "the 1.20.4 sound reaches 1.13 by name");
    require(toLegacy.seed() == 0, "1.13 has no seed field, so the seed is dropped");
    require(toLegacy.volume() == 0.75f && toLegacy.pitch() == 1.25f, "volume and pitch survive");
  }

  /**
   * A sound 1.13's registry does not have still reaches a 1.13 client.
   *
   * <p>1.20.4 can name a sound inline; 1.13 can only index its registry, so a
   * modern sound would have nothing to be written as. It is sent as Named Sound
   * Effect instead, which is the packet 1.19.3 folded into the inline form.
   */
  private static void aSoundOnlyOneSideHasIsNamedRatherThanDropped() throws Exception {
    String modernOnly = "minecraft:entity.warden.roar";
    require(SoundRegistries.id(393, modernOnly).isEmpty(), "1.13 has no warden");
    int id765 = SoundRegistries.id(765, modernOnly).orElseThrow();

    ProtocolTranslator translator = AllTests.nativePair(393, 765);   // 1.13 client, 1.20.4 backend
    byte[] packet = PlayPackets.withId(
        ProtocolDefinition.forVersion(765).id(ConnectionState.PLAY,
            PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SOUND_EFFECT),
        soundEffect765(id765 + 1, 0, 8, 16, 24, 1f, 1f, 0L));
    byte[] out = translator.backendToClient(ConnectionState.PLAY, packet);
    require(PlayPackets.packetId(out) == ProtocolDefinition.forVersion(393).id(ConnectionState.PLAY,
            PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_NAMED_SOUND_EFFECT),
        "a 1.13 client is sent Named Sound Effect for a sound it has no id for");
    SoundCodec.Sound named = SoundCodec.readNamed(PlayPackets.body(out));
    require(named.name().equals(modernOnly), "and it names the sound that was played");
  }

  /** 1.13's Named Sound Effect becomes 1.20.4's inline Sound Effect, keeping the name. */
  private static void namedSoundBecomesAnInlineSoundAndBack() throws Exception {
    String pluginSound = "myserver:ui.click";
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(body)) {
      MinecraftOutput.string(out, pluginSound);
      MinecraftOutput.varInt(out, 4);
      out.writeInt(1); out.writeInt(2); out.writeInt(3);
      out.writeFloat(0.5f); out.writeFloat(2f);
    }
    byte[] translated = translate(393, 765, PacketKind.PLAY_NAMED_SOUND_EFFECT, body.toByteArray());
    SoundCodec.Sound sound = SoundCodec.read(765, translated);
    require(sound.name().equals(pluginSound), "a sound of a plugin's own keeps its name");
    require(sound.inline(), "and is carried inline, since no registry has it");
    require(sound.category() == 4 && sound.volume() == 0.5f && sound.pitch() == 2f,
        "its category, volume and pitch survive");
  }

  /** A body that is not a sound effect is dropped, never forwarded as bytes. */
  private static void malformedBodiesFailClosed() {
    require(SoundCodec.read(765, new byte[0]) == null, "an empty body is not a sound");
    require(SoundCodec.read(393, new byte[] {1, 2, 3}) == null, "a truncated body is not a sound");
    byte[] valid = soundEffect393(SoundRegistries.id(393, "minecraft:ambient.cave").orElseThrow(),
        0, 1, 2, 3, 1f, 1f);
    byte[] trailing = java.util.Arrays.copyOf(valid, valid.length + 1);
    require(SoundCodec.read(393, trailing) == null, "trailing bytes mean it is not a sound effect");
    require(SoundCodec.read(393, new byte[] {(byte) 0xFF, (byte) 0xFF, 1}) == null,
        "an id outside the registry is not resolved");
  }

  /**
   * A clientbound packet a {@code from} backend sends, as the {@code to} client receives it.
   *
   * <p>Direction is part of a pair's identity: the translator keyed
   * {@code (client, backend)} is the one that carries that backend's packets to
   * that client. So a 1.13 backend talking to a 1.20.4 client is the pair
   * {@code (765, 393)}, not {@code (393, 765)}, which is its mirror.
   */
  private static byte[] translate(int from, int to, PacketKind kind, byte[] body) throws Exception {
    ProtocolTranslator translator = AllTests.nativePair(to, from);
    byte[] packet = PlayPackets.withId(ProtocolDefinition.forVersion(from)
        .id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, kind), body);
    byte[] out = translator.backendToClient(ConnectionState.PLAY, packet);
    require(out != null, "the " + from + " -> " + to + " translator dropped the packet");
    return PlayPackets.body(out);
  }

  private static byte[] soundEffect393(int id, int category, int x, int y, int z,
                                       float volume, float pitch) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(out, id);
      MinecraftOutput.varInt(out, category);
      out.writeInt(x); out.writeInt(y); out.writeInt(z);
      out.writeFloat(volume); out.writeFloat(pitch);
    } catch (java.io.IOException impossible) {
      throw new AssertionError(impossible);
    }
    return bytes.toByteArray();
  }

  private static byte[] soundEffect765(int holder, int category, int x, int y, int z,
                                       float volume, float pitch, long seed) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(out, holder);
      MinecraftOutput.varInt(out, category);
      out.writeInt(x); out.writeInt(y); out.writeInt(z);
      out.writeFloat(volume); out.writeFloat(pitch);
      out.writeLong(seed);
    } catch (java.io.IOException impossible) {
      throw new AssertionError(impossible);
    }
    return bytes.toByteArray();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
