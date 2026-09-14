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
}