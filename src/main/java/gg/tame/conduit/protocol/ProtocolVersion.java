package gg.tame.conduit.protocol;

/**
 * Protocol identities. Listing a version here does not mean Conduit can proxy it.
 * Packet codecs exist only for versions returned by {@link ProtocolDefinition#forVersion(int)}.
 */
public record ProtocolVersion(int number, String displayName) {
  public static final ProtocolVersion MINECRAFT_1_7_10 = new ProtocolVersion(5, "1.7.10");
  public static final ProtocolVersion MINECRAFT_1_8_9 = new ProtocolVersion(47, "1.8.9");
  public static final ProtocolVersion MINECRAFT_1_12_2 = new ProtocolVersion(340, "1.12.2");
  public static final ProtocolVersion MINECRAFT_1_16_5 = new ProtocolVersion(754, "1.16.5");
  public static final ProtocolVersion MINECRAFT_1_19_4 = new ProtocolVersion(762, "1.19.4");
  public static final ProtocolVersion MINECRAFT_1_20_1 = new ProtocolVersion(763, "1.20.1");
  public static final ProtocolVersion MINECRAFT_1_20_4 = new ProtocolVersion(765, "1.20.4");
  public static final ProtocolVersion MINECRAFT_1_21_4 = new ProtocolVersion(769, "1.21.4");
}
