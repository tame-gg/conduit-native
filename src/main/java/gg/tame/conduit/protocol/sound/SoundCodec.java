// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol.sound;

import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;

/**
 * The Sound Effect packet across the 1.13 / 1.20.4 boundary.
 *
 * <p>The two layouts differ in more than the sound id:
 *
 * <pre>
 *   1.13  (393)   VarInt id | VarInt category | i32 x*8 | i32 y*8 | i32 z*8 | f32 volume | f32 pitch
 *   1.20.4 (765)  VarInt id+1 [| inline definition when 0] | VarInt category
 *                 | i32 x*8 | i32 y*8 | i32 z*8 | f32 volume | f32 pitch | i64 seed
 * </pre>
 *
 * <p>1.20.4 carries the sound as a registry holder: the id is written one higher
 * so that zero can mean "an inline definition follows" — a name, and an optional
 * fixed range. A packet that uses the inline form names its sound directly, so it
 * needs no table; one that uses the id needs {@link SoundRegistries}, because the
 * same index is a different sound on each side.
 *
 * <p>The seed 1.20.4 added picks between a sound's variants. 1.13 has no field
 * for it, so toward 1.13 it is dropped, and toward 1.20.4 a zero is written,
 * which is the value the server sends when it has not chosen a variant.
 *
 * <p>Everything here is bounded and fails closed: a body that is truncated, has
 * trailing bytes, or names a sound the other side does not have yields null, and
 * the caller drops that one packet rather than forwarding bytes the client would
 * read as a different packet.
 */
public final class SoundCodec {
  /** The longest sound identifier accepted from an inline definition. */
  private static final int MAXIMUM_IDENTIFIER_BYTES = 256;

  private SoundCodec() {}

  /**
   * A Sound Effect, as the fields both eras share plus the ones only one has.
   *
   * @param inline the sound was sent as an inline definition rather than a registry id, which
   *     1.20.4 allows and 1.13 does not; kept so an inline sound stays inline
   * @param fixedRange the range an inline definition may carry, or a negative value for none
   */
  public record Sound(String name, int category, int x, int y, int z, float volume, float pitch,
                      long seed, boolean inline, float fixedRange) {
    public boolean hasFixedRange() {
      return fixedRange >= 0;
    }
  }

  /**
   * Reads a Sound Effect body written by {@code protocol}, or null when it is not one.
   *
   * <p>The sound is resolved to its name here rather than kept as an index, so the
   * rest of the translation is a name lookup on the far side and an index that
   * means nothing there can never be written.
   */
  public static Sound read(int protocol, byte[] body) {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
      String name;
      boolean inline = false;
      float fixedRange = -1;
      if (protocol >= 765) {
        int holder = MinecraftInput.varInt(input);
        if (holder == 0) {
          inline = true;
          name = MinecraftInput.string(input, MAXIMUM_IDENTIFIER_BYTES);
          if (input.readBoolean()) fixedRange = input.readFloat();
        } else {
          name = SoundRegistries.name(protocol, holder - 1).orElse(null);
          if (name == null) return null;
        }
      } else {
        name = SoundRegistries.name(protocol, MinecraftInput.varInt(input)).orElse(null);
        if (name == null) return null;
      }

      int category = MinecraftInput.varInt(input);
      int x = input.readInt();
      int y = input.readInt();
      int z = input.readInt();
      float volume = input.readFloat();
      float pitch = input.readFloat();
      long seed = protocol >= 765 ? input.readLong() : 0L;
      if (input.read() != -1) return null;                 // trailing bytes: not this packet
      return new Sound(name, category, x, y, z, volume, pitch, seed, inline, fixedRange);
    } catch (EOFException truncated) {
      return null;
    } catch (IOException malformed) {
      return null;
    }
  }

  /**
   * Writes a Sound Effect body for {@code protocol}, or null when it cannot carry this sound.
   *
   * <p>Toward 1.20.4 an inline sound stays inline, so a name that side has no
   * registry entry for still reaches the client. Toward 1.13 there is no inline
   * form, so a sound absent from the 1.13 registry cannot be expressed at all and
   * the packet is dropped.
   */
  public static byte[] write(int protocol, Sound sound) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      if (protocol >= 765) {
        int id = SoundRegistries.id(protocol, sound.name()).orElse(-1);
        if (id >= 0 && !sound.inline()) {
          MinecraftOutput.varInt(output, id + 1);
        } else {
          MinecraftOutput.varInt(output, 0);
          MinecraftOutput.string(output, sound.name());
          output.writeBoolean(sound.hasFixedRange());
          if (sound.hasFixedRange()) output.writeFloat(sound.fixedRange());
        }
      } else {
        int id = SoundRegistries.id(protocol, sound.name()).orElse(-1);
        if (id < 0) return null;
        MinecraftOutput.varInt(output, id);
      }
      MinecraftOutput.varInt(output, sound.category());
      output.writeInt(sound.x());
      output.writeInt(sound.y());
      output.writeInt(sound.z());
      output.writeFloat(sound.volume());
      output.writeFloat(sound.pitch());
      if (protocol >= 765) output.writeLong(sound.seed());
    } catch (IOException impossible) {
      return null;
    }
    return bytes.toByteArray();
  }

  /**
   * Reads a 1.13 Named Sound Effect body, or null when it is not one.
   *
   * <p>Named Sound Effect names its sound instead of indexing the registry, which
   * is how a plugin plays a sound of its own. 1.19.3 removed the packet and folded
   * it into Sound Effect's inline form, so 1.20.4 has no counterpart packet — only
   * a counterpart shape.
   *
   * <pre>
   *   1.13 (393)  String name | VarInt category | i32 x*8 | i32 y*8 | i32 z*8 | f32 volume | f32 pitch
   * </pre>
   */
  public static Sound readNamed(byte[] body) {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
      String name = MinecraftInput.string(input, MAXIMUM_IDENTIFIER_BYTES);
      int category = MinecraftInput.varInt(input);
      int x = input.readInt();
      int y = input.readInt();
      int z = input.readInt();
      float volume = input.readFloat();
      float pitch = input.readFloat();
      if (input.read() != -1) return null;
      return new Sound(name, category, x, y, z, volume, pitch, 0L, true, -1);
    } catch (EOFException truncated) {
      return null;
    } catch (IOException malformed) {
      return null;
    }
  }

  /** Writes a 1.13 Named Sound Effect body. Any sound can be written: it carries its own name. */
  public static byte[] writeNamed(Sound sound) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.string(output, sound.name());
      MinecraftOutput.varInt(output, sound.category());
      output.writeInt(sound.x());
      output.writeInt(sound.y());
      output.writeInt(sound.z());
      output.writeFloat(sound.volume());
      output.writeFloat(sound.pitch());
    } catch (IOException impossible) {
      return null;
    }
    return bytes.toByteArray();
  }

  /**
   * Translates a Sound Effect body from one protocol to the other.
   *
   * @return the translated body, or null when this sound has no counterpart and
   *     the packet must be dropped
   */
  public static byte[] translate(int fromProtocol, int toProtocol, byte[] body) {
    Sound sound = read(fromProtocol, body);
    return sound == null ? null : write(toProtocol, sound);
  }
}
