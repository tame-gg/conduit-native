// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Protocol identities. A catalog entry is not a codec.
 * Modern compatibility program: 1.13 (393) through 26.3 (777).
 * ≤1.12.2 is LEGACY / OUT OF SCOPE for this program.
 */
public record ProtocolVersion(int number, String displayName, ProtocolEra era, ProtocolFamily family) {
  public ProtocolVersion(int number, String displayName) {
    this(number, displayName, eraOf(number), ProtocolFamily.ofProtocol(number));
  }

  public ProtocolVersion(int number, String displayName, ProtocolEra era) {
    this(number, displayName, era, ProtocolFamily.ofProtocol(number));
  }

  // Legacy — catalog only, OUT OF SCOPE for modern program.
  public static final ProtocolVersion MINECRAFT_1_7_10 = new ProtocolVersion(5, "1.7.10", ProtocolEra.LEGACY, ProtocolFamily.LEGACY_OUT_OF_SCOPE);
  public static final ProtocolVersion MINECRAFT_1_8_9 = new ProtocolVersion(47, "1.8.9", ProtocolEra.LEGACY, ProtocolFamily.LEGACY_OUT_OF_SCOPE);
  public static final ProtocolVersion MINECRAFT_1_12_2 = new ProtocolVersion(340, "1.12.2", ProtocolEra.CLASSIC_MODERN, ProtocolFamily.LEGACY_OUT_OF_SCOPE);

  // Modern program — unique protocol numbers (shared releases use MinecraftRelease aliases).
  public static final ProtocolVersion MINECRAFT_1_13 = new ProtocolVersion(393, "1.13", ProtocolEra.FLATTENING, ProtocolFamily.V1_13);
  public static final ProtocolVersion MINECRAFT_1_13_1 = new ProtocolVersion(401, "1.13.1", ProtocolEra.FLATTENING, ProtocolFamily.V1_13);
  public static final ProtocolVersion MINECRAFT_1_13_2 = new ProtocolVersion(404, "1.13.2", ProtocolEra.FLATTENING, ProtocolFamily.V1_13);
  public static final ProtocolVersion MINECRAFT_1_14 = new ProtocolVersion(477, "1.14", ProtocolEra.FLATTENING, ProtocolFamily.V1_14);
  public static final ProtocolVersion MINECRAFT_1_14_1 = new ProtocolVersion(480, "1.14.1", ProtocolEra.FLATTENING, ProtocolFamily.V1_14);
  public static final ProtocolVersion MINECRAFT_1_14_2 = new ProtocolVersion(485, "1.14.2", ProtocolEra.FLATTENING, ProtocolFamily.V1_14);
  public static final ProtocolVersion MINECRAFT_1_14_3 = new ProtocolVersion(490, "1.14.3", ProtocolEra.FLATTENING, ProtocolFamily.V1_14);
  public static final ProtocolVersion MINECRAFT_1_14_4 = new ProtocolVersion(498, "1.14.4", ProtocolEra.FLATTENING, ProtocolFamily.V1_14);
  public static final ProtocolVersion MINECRAFT_1_15 = new ProtocolVersion(573, "1.15", ProtocolEra.FLATTENING, ProtocolFamily.V1_15);
  public static final ProtocolVersion MINECRAFT_1_15_1 = new ProtocolVersion(575, "1.15.1", ProtocolEra.FLATTENING, ProtocolFamily.V1_15);
  public static final ProtocolVersion MINECRAFT_1_15_2 = new ProtocolVersion(578, "1.15.2", ProtocolEra.FLATTENING, ProtocolFamily.V1_15);
  public static final ProtocolVersion MINECRAFT_1_16 = new ProtocolVersion(735, "1.16", ProtocolEra.FLATTENING, ProtocolFamily.V1_16);
  public static final ProtocolVersion MINECRAFT_1_16_1 = new ProtocolVersion(736, "1.16.1", ProtocolEra.FLATTENING, ProtocolFamily.V1_16);
  public static final ProtocolVersion MINECRAFT_1_16_2 = new ProtocolVersion(751, "1.16.2", ProtocolEra.FLATTENING, ProtocolFamily.V1_16);
  public static final ProtocolVersion MINECRAFT_1_16_3 = new ProtocolVersion(753, "1.16.3", ProtocolEra.FLATTENING, ProtocolFamily.V1_16);
  public static final ProtocolVersion MINECRAFT_1_16_5 = new ProtocolVersion(754, "1.16.5", ProtocolEra.FLATTENING, ProtocolFamily.V1_16);
  public static final ProtocolVersion MINECRAFT_1_17 = new ProtocolVersion(755, "1.17", ProtocolEra.FLATTENING, ProtocolFamily.V1_17);
  public static final ProtocolVersion MINECRAFT_1_17_1 = new ProtocolVersion(756, "1.17.1", ProtocolEra.FLATTENING, ProtocolFamily.V1_17);
  public static final ProtocolVersion MINECRAFT_1_18 = new ProtocolVersion(757, "1.18", ProtocolEra.FLATTENING, ProtocolFamily.V1_18);
  public static final ProtocolVersion MINECRAFT_1_18_2 = new ProtocolVersion(758, "1.18.2", ProtocolEra.FLATTENING, ProtocolFamily.V1_18);
  public static final ProtocolVersion MINECRAFT_1_19 = new ProtocolVersion(759, "1.19", ProtocolEra.FLATTENING, ProtocolFamily.V1_19);
  public static final ProtocolVersion MINECRAFT_1_19_2 = new ProtocolVersion(760, "1.19.2", ProtocolEra.FLATTENING, ProtocolFamily.V1_19);
  public static final ProtocolVersion MINECRAFT_1_19_3 = new ProtocolVersion(761, "1.19.3", ProtocolEra.FLATTENING, ProtocolFamily.V1_19);
  public static final ProtocolVersion MINECRAFT_1_19_4 = new ProtocolVersion(762, "1.19.4", ProtocolEra.FLATTENING, ProtocolFamily.V1_19);
  public static final ProtocolVersion MINECRAFT_1_20_1 = new ProtocolVersion(763, "1.20.1", ProtocolEra.FLATTENING, ProtocolFamily.V1_20);
  public static final ProtocolVersion MINECRAFT_1_20_2 = new ProtocolVersion(764, "1.20.2", ProtocolEra.CONFIGURATION, ProtocolFamily.V1_20);
  public static final ProtocolVersion MINECRAFT_1_20_4 = new ProtocolVersion(765, "1.20.4", ProtocolEra.CONFIGURATION, ProtocolFamily.V1_20);
  public static final ProtocolVersion MINECRAFT_1_20_5 = new ProtocolVersion(766, "1.20.5", ProtocolEra.CONFIGURATION, ProtocolFamily.V1_20);
  public static final ProtocolVersion MINECRAFT_1_21 = new ProtocolVersion(767, "1.21", ProtocolEra.CURRENT, ProtocolFamily.V1_21);
  public static final ProtocolVersion MINECRAFT_1_21_3 = new ProtocolVersion(768, "1.21.3", ProtocolEra.CURRENT, ProtocolFamily.V1_21);
  public static final ProtocolVersion MINECRAFT_1_21_4 = new ProtocolVersion(769, "1.21.4", ProtocolEra.CURRENT, ProtocolFamily.V1_21);
  public static final ProtocolVersion MINECRAFT_1_21_5 = new ProtocolVersion(770, "1.21.5", ProtocolEra.CURRENT, ProtocolFamily.V1_21);
  public static final ProtocolVersion MINECRAFT_1_21_6 = new ProtocolVersion(771, "1.21.6", ProtocolEra.CURRENT, ProtocolFamily.V1_21);
  public static final ProtocolVersion MINECRAFT_1_21_8 = new ProtocolVersion(772, "1.21.8", ProtocolEra.CURRENT, ProtocolFamily.V1_21);
  public static final ProtocolVersion MINECRAFT_1_21_10 = new ProtocolVersion(773, "1.21.10", ProtocolEra.CURRENT, ProtocolFamily.V1_21);
  public static final ProtocolVersion MINECRAFT_1_21_11 = new ProtocolVersion(774, "1.21.11", ProtocolEra.CURRENT, ProtocolFamily.V1_21);
  public static final ProtocolVersion MINECRAFT_26_1 = new ProtocolVersion(775, "26.1", ProtocolEra.CURRENT, ProtocolFamily.V26);
  public static final ProtocolVersion MINECRAFT_26_2 = new ProtocolVersion(776, "26.2", ProtocolEra.CURRENT, ProtocolFamily.V26);
  public static final ProtocolVersion MINECRAFT_26_3 = new ProtocolVersion(777, "26.3", ProtocolEra.CURRENT, ProtocolFamily.V26);

  /** Unique protocol-number identities (one entry per protocol number). */
  public static final List<ProtocolVersion> CATALOG = List.of(
      MINECRAFT_1_7_10, MINECRAFT_1_8_9, MINECRAFT_1_12_2,
      MINECRAFT_1_13, MINECRAFT_1_13_1, MINECRAFT_1_13_2,
      MINECRAFT_1_14, MINECRAFT_1_14_1, MINECRAFT_1_14_2, MINECRAFT_1_14_3, MINECRAFT_1_14_4,
      MINECRAFT_1_15, MINECRAFT_1_15_1, MINECRAFT_1_15_2,
      MINECRAFT_1_16, MINECRAFT_1_16_1, MINECRAFT_1_16_2, MINECRAFT_1_16_3, MINECRAFT_1_16_5,
      MINECRAFT_1_17, MINECRAFT_1_17_1,
      MINECRAFT_1_18, MINECRAFT_1_18_2,
      MINECRAFT_1_19, MINECRAFT_1_19_2, MINECRAFT_1_19_3, MINECRAFT_1_19_4,
      MINECRAFT_1_20_1, MINECRAFT_1_20_2, MINECRAFT_1_20_4, MINECRAFT_1_20_5,
      MINECRAFT_1_21, MINECRAFT_1_21_3, MINECRAFT_1_21_4, MINECRAFT_1_21_5, MINECRAFT_1_21_6,
      MINECRAFT_1_21_8, MINECRAFT_1_21_10, MINECRAFT_1_21_11,
      MINECRAFT_26_1, MINECRAFT_26_2, MINECRAFT_26_3);

  /**
   * Named releases including aliases that share a protocol number.
   * Protocol numbers from Minecraft Wiki / PrismarineJS public data — not invented.
   */
  public static final List<MinecraftRelease> RELEASES = buildReleases();

  private static List<MinecraftRelease> buildReleases() {
    List<MinecraftRelease> list = new ArrayList<>();
    // Legacy out of scope
    list.add(rel("1.7.10", 5, false));
    list.add(rel("1.8.9", 47, false));
    list.add(rel("1.12.2", 340, false));
    // Modern program
    list.add(rel("1.13", 393, true));
    list.add(rel("1.13.1", 401, true));
    list.add(rel("1.13.2", 404, true));
    list.add(rel("1.14", 477, true));
    list.add(rel("1.14.1", 480, true));
    list.add(rel("1.14.2", 485, true));
    list.add(rel("1.14.3", 490, true));
    list.add(rel("1.14.4", 498, true));
    list.add(rel("1.15", 573, true));
    list.add(rel("1.15.1", 575, true));
    list.add(rel("1.15.2", 578, true));
    list.add(rel("1.16", 735, true));
    list.add(rel("1.16.1", 736, true));
    list.add(rel("1.16.2", 751, true));
    list.add(rel("1.16.3", 753, true));
    list.add(rel("1.16.4", 754, true));
    list.add(rel("1.16.5", 754, true));
    list.add(rel("1.17", 755, true));
    list.add(rel("1.17.1", 756, true));
    list.add(rel("1.18", 757, true));
    list.add(rel("1.18.1", 757, true));
    list.add(rel("1.18.2", 758, true));
    list.add(rel("1.19", 759, true));
    list.add(rel("1.19.1", 760, true));
    list.add(rel("1.19.2", 760, true));
    list.add(rel("1.19.3", 761, true));
    list.add(rel("1.19.4", 762, true));
    list.add(rel("1.20", 763, true));
    list.add(rel("1.20.1", 763, true));
    list.add(rel("1.20.2", 764, true));
    list.add(rel("1.20.3", 765, true));
    list.add(rel("1.20.4", 765, true));
    list.add(rel("1.20.5", 766, true));
    list.add(rel("1.20.6", 766, true));
    list.add(rel("1.21", 767, true));
    list.add(rel("1.21.1", 767, true));
    list.add(rel("1.21.2", 768, true));
    list.add(rel("1.21.3", 768, true));
    list.add(rel("1.21.4", 769, true));
    list.add(rel("1.21.5", 770, true));
    list.add(rel("1.21.6", 771, true));
    list.add(rel("1.21.7", 772, true));
    list.add(rel("1.21.8", 772, true));
    list.add(rel("1.21.9", 773, true));
    list.add(rel("1.21.10", 773, true));
    list.add(rel("1.21.11", 774, true));
    list.add(rel("26.1", 775, true));
    list.add(rel("26.1.1", 775, true));
    list.add(rel("26.1.2", 775, true));
    list.add(rel("26.2", 776, true));
    list.add(rel("26.3", 777, true));
    return List.copyOf(list);
  }

  private static MinecraftRelease rel(String name, int protocol, boolean modern) {
    return new MinecraftRelease(name, protocol, ProtocolFamily.ofProtocol(protocol), modern);
  }

  public static ProtocolEra eraOf(int number) {
    if (number < 393) {
      if (number <= 47) return ProtocolEra.LEGACY;
      return ProtocolEra.CLASSIC_MODERN;
    }
    if (number <= 762) return ProtocolEra.FLATTENING;
    if (number <= 766) return ProtocolEra.CONFIGURATION;
    return ProtocolEra.CURRENT;
  }

  public static String display(int number) {
    for (ProtocolVersion version : CATALOG) if (version.number() == number) return version.displayName();
    // A release newer than this catalog, which ViaVersion may well have a name for. "protocol 778"
    // in a kick message or a server list entry tells a player nothing they can act on.
    var carried = gg.tame.conduit.viaversion.ConduitViaSupport.knownName(number);
    return carried.orElse("protocol " + number);
  }

  public static Map<Integer, ProtocolVersion> byNumber() {
    Map<Integer, ProtocolVersion> map = new LinkedHashMap<>();
    for (ProtocolVersion version : CATALOG) map.putIfAbsent(version.number(), version);
    return Map.copyOf(map);
  }

  public boolean modernProgram() {
    return family.isModernProgram();
  }
}
