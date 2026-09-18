// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.player;

import java.util.Objects;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/**
 * A sound for {@link Player#playSound}, named as the client's sound list names it, such as
 * {@code minecraft:entity.experience_orb.pickup}. Clients before 1.9 use their own older names, such
 * as {@code random.orb}; a name a client does not know plays nothing.
 *
 * @param volume from 0; 1 is normal, and above 1 only makes the sound heard further away
 * @param pitch  0.5 to 2 as the client plays it; 1 is normal
 * @param seed   picks among the sound's variants on 1.19+ clients; random when empty
 */
public record Sound(String name, Source source, float volume, float pitch, OptionalLong seed) {
  /** The client's volume slider a sound plays under. {@code UI} is new in 26.1; older clients play it as {@code MASTER}. */
  public enum Source { MASTER, MUSIC, RECORD, WEATHER, BLOCK, HOSTILE, NEUTRAL, PLAYER, AMBIENT, VOICE, UI }

  // A resource location, which is what 1.13+ clients read the name as; they drop the connection over
  // one they cannot parse, so a bad name is refused here instead.
  private static final Pattern NAME = Pattern.compile("([a-z0-9_.-]+:)?[a-z0-9_./-]+");

  public Sound {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(source, "source");
    if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("not a sound name: " + name);
    if (!(volume >= 0) || Float.isInfinite(volume)) throw new IllegalArgumentException("volume must be finite and >= 0, was " + volume);
    if (!Float.isFinite(pitch)) throw new IllegalArgumentException("pitch must be finite, was " + pitch);
    if (seed == null) seed = OptionalLong.empty();
  }

  /** A sound with a random seed. */
  public static Sound of(String name, Source source, float volume, float pitch) {
    return new Sound(name, source, volume, pitch, OptionalLong.empty());
  }

  public Sound withSeed(long seed) { return new Sound(name, source, volume, pitch, OptionalLong.of(seed)); }
}
