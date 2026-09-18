// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Catalog of protocol identities and named releases.
 * A catalog entry is not a codec — {@link ProtocolDefinition#hasCodec(int)} is authoritative.
 */
public final class ProtocolCatalog {
  private ProtocolCatalog() {}

  public static Optional<ProtocolVersion> find(int number) {
    return Optional.ofNullable(ProtocolVersion.byNumber().get(number));
  }

  public static Optional<MinecraftRelease> findRelease(String name) {
    if (name == null || name.isBlank()) return Optional.empty();
    String needle = name.strip().toLowerCase(Locale.ROOT);
    for (MinecraftRelease release : ProtocolVersion.RELEASES) {
      if (release.name().equalsIgnoreCase(needle)) return Optional.of(release);
    }
    return Optional.empty();
  }

  public static List<ProtocolVersion> all() {
    return ProtocolVersion.CATALOG;
  }

  public static List<ProtocolVersion> modern() {
    return ProtocolVersion.CATALOG.stream().filter(ProtocolVersion::modernProgram).toList();
  }

  public static List<ProtocolVersion> legacyOutOfScope() {
    return ProtocolVersion.CATALOG.stream().filter(v -> !v.modernProgram()).toList();
  }

  public static List<ProtocolVersion> withCodecs() {
    return ProtocolVersion.CATALOG.stream()
        .filter(v -> ProtocolDefinition.hasCodec(v.number()))
        .collect(Collectors.toList());
  }

  public static Collection<Integer> codecNumbers() {
    return withCodecs().stream().map(ProtocolVersion::number).toList();
  }

  public static List<MinecraftRelease> releases() {
    return ProtocolVersion.RELEASES;
  }

  public static List<MinecraftRelease> modernReleases() {
    return ProtocolVersion.RELEASES.stream().filter(MinecraftRelease::modernProgram).toList();
  }

  public static String describeSupport(int client, int backend) {
    CompatibilityEntry entry = CompatibilityRegistry.resolve(client, backend);
    return client + "→" + backend + "=" + entry.support() + "/" + entry.completeness();
  }

  public static boolean inModernProgram(int protocol) {
    return ProtocolFamily.ofProtocol(protocol).isModernProgram();
  }
}
