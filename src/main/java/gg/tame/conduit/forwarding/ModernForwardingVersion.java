// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.forwarding;

/** Modern forwarding format version (channel velocity:player_info), not the Minecraft protocol number. */
public final class ModernForwardingVersion {
  public static final int V1_DEFAULT = 1;
  public static final int V2_WITH_KEY = 2;
  public static final int V3_WITH_KEY_V2 = 3;
  public static final int V4_LAZY_SESSION = 4;
  public static final int MIN = V1_DEFAULT;
  public static final int MAX = V4_LAZY_SESSION;
  private ModernForwardingVersion() {}
  public static boolean supported(int version) {
    return version >= MIN && version <= MAX;
  }

  /**
   * The version Conduit answers a backend's request in. The request names the highest version the
   * backend reads, not one it demands. Versions 2 and 3 carry the player's chat signing key, which
   * Conduit does not forward, so those are answered in version 1: a real Paper 1.19 asked for 2 and
   * accepted 1, while "2" written without the key failed on the backend reading the key's expiry.
   */
  public static int answerFor(int requested) {
    return requested == V2_WITH_KEY || requested == V3_WITH_KEY_V2 ? V1_DEFAULT : requested;
  }
}