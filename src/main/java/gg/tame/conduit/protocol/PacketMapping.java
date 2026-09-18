// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol;

/**
 * One packet's numeric id within a single protocol version.
 *
 * <p>The numeric id belongs to the protocol; {@link PacketKind} is the
 * version-independent identity. Translators address packets by kind and never
 * by id, so no translation path may derive a target id arithmetically from a
 * source id.
 */
public record PacketMapping(ConnectionState state, PacketDirection direction, PacketKind kind, int id) {

  /** Sentinel id meaning "this kind does not exist in this protocol". */
  public static final int ABSENT = -1;

  public PacketMapping {
    if (state == null || direction == null || kind == null) {
      throw new IllegalArgumentException("packet mapping requires state, direction and kind");
    }
    if (id < ABSENT) {
      throw new IllegalArgumentException("invalid packet id " + id + " for " + kind);
    }
  }

  public static PacketMapping of(ConnectionState state, PacketDirection direction, PacketKind kind, int id) {
    return new PacketMapping(state, direction, kind, id);
  }

  /** Marks a packet that a later protocol removed, so derived tables stop exposing it. */
  public static PacketMapping removed(ConnectionState state, PacketDirection direction, PacketKind kind) {
    return new PacketMapping(state, direction, kind, ABSENT);
  }

  public boolean present() {
    return id != ABSENT;
  }
}
