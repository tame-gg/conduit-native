package gg.tame.conduit.protocol;

import java.util.List;

/** Protocol identities. A catalog entry is not a codec. */
public record ProtocolVersion(int number, String displayName, ProtocolEra era) {
  public ProtocolVersion(int number, String displayName) {
    this(number, displayName, eraOf(number));
  }
  public static final ProtocolVersion MINECRAFT_1_7_10 = new ProtocolVersion(5, "1.7.10", ProtocolEra.LEGACY);
  public static final ProtocolVersion MINECRAFT_1_8_9 = new ProtocolVersion(47, "1.8.9", ProtocolEra.LEGACY);
  public static final ProtocolVersion MINECRAFT_1_12_2 = new ProtocolVersion(340, "1.12.2", ProtocolEra.CLASSIC_MODERN);
  public static final ProtocolVersion MINECRAFT_1_16_5 = new ProtocolVersion(754, "1.16.5", ProtocolEra.FLATTENING);
  public static final ProtocolVersion MINECRAFT_1_19_4 = new ProtocolVersion(762, "1.19.4", ProtocolEra.FLATTENING);
  public static final ProtocolVersion MINECRAFT_1_20_1 = new ProtocolVersion(763, "1.20.1", ProtocolEra.FLATTENING);
  public static final ProtocolVersion MINECRAFT_1_20_2 = new ProtocolVersion(764, "1.20.2", ProtocolEra.CONFIGURATION);
  public static final ProtocolVersion MINECRAFT_1_20_4 = new ProtocolVersion(765, "1.20.4", ProtocolEra.CONFIGURATION);
  public static final ProtocolVersion MINECRAFT_1_21 = new ProtocolVersion(767, "1.21", ProtocolEra.CURRENT);
  public static final ProtocolVersion MINECRAFT_1_21_4 = new ProtocolVersion(769, "1.21.4", ProtocolEra.CURRENT);
  public static final ProtocolVersion MINECRAFT_1_21_8 = new ProtocolVersion(772, "1.21.8", ProtocolEra.CURRENT);
  public static final ProtocolVersion MINECRAFT_26_2 = new ProtocolVersion(776, "26.2", ProtocolEra.CURRENT);
  public static final List<ProtocolVersion> CATALOG = List.of(
      MINECRAFT_1_7_10, MINECRAFT_1_8_9, MINECRAFT_1_12_2, MINECRAFT_1_16_5, MINECRAFT_1_19_4,
      MINECRAFT_1_20_1, MINECRAFT_1_20_2, MINECRAFT_1_20_4, MINECRAFT_1_21, MINECRAFT_1_21_4,
      MINECRAFT_1_21_8, MINECRAFT_26_2);
  public static ProtocolEra eraOf(int number) {
    if (number <= 47) return ProtocolEra.LEGACY;
    if (number <= 340) return ProtocolEra.CLASSIC_MODERN;
    if (number <= 763) return ProtocolEra.FLATTENING;
    if (number <= 765) return ProtocolEra.CONFIGURATION;
    return ProtocolEra.CURRENT;
  }
  public static String display(int number) {
    for (ProtocolVersion version : CATALOG) if (version.number() == number) return version.displayName();
    return "protocol " + number;
  }
}
