// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol.codec;

import gg.tame.conduit.protocol.ProtocolDefinition;

/**
 * Packed block position codec.
 *
 * <p>The field order inside the 64-bit position changed in 1.14:
 * <ul>
 *   <li><b>Legacy (1.13 / 393)</b> — {@code x:26, y:12, z:26}, with Y in the middle.</li>
 *   <li><b>Modern (1.14+ / 765)</b> — {@code x:26, z:26, y:12}, with Y in the low bits.</li>
 * </ul>
 *
 * <p>The two are the same width, so a raw forward looks valid and silently lands the block
 * somewhere else entirely — a modern (-38, 70, 28) read as legacy becomes (-38, 0, 114758).
 * Every position crossing the era boundary must be unpacked and repacked.
 */
public final class BlockPositionCodec {
  /** Highest protocol number still packing Y in the middle of the position long. */
  private static final int LEGACY_MAX_PROTOCOL = 404;

  private BlockPositionCodec() {}

  /** A decoded block position. */
  public record BlockPosition(int x, int y, int z) {}

  private static boolean legacy(ProtocolDefinition protocol) {
    return protocol.version().number() <= LEGACY_MAX_PROTOCOL;
  }

  /** Sign-extends the low {@code bits} of {@code value}. */
  private static int signExtend(long value, int bits) {
    long masked = value & ((1L << bits) - 1);
    long signBit = 1L << (bits - 1);
    return (int) ((masked ^ signBit) - signBit);
  }

  /** Unpacks a position written in the era of {@code protocol}. */
  public static BlockPosition unpack(ProtocolDefinition protocol, long packed) {
    int x = signExtend(packed >> 38, 26);
    if (legacy(protocol)) {
      return new BlockPosition(x, signExtend(packed >> 26, 12), signExtend(packed, 26));
    }
    return new BlockPosition(x, signExtend(packed, 12), signExtend(packed >> 12, 26));
  }

  /** Packs a position for the era of {@code protocol}. */
  public static long pack(ProtocolDefinition protocol, BlockPosition position) {
    long x = position.x() & 0x3FFFFFFL;
    long y = position.y() & 0xFFFL;
    long z = position.z() & 0x3FFFFFFL;
    return legacy(protocol) ? (x << 38) | (y << 26) | z : (x << 38) | (z << 12) | y;
  }

  /** Re-packs a position from the {@code source} era into the {@code target} era. */
  public static long translate(ProtocolDefinition source, ProtocolDefinition target, long packed) {
    return pack(target, unpack(source, packed));
  }
}
