// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.viaversion;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.protocol.ProtocolPathEntry;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import java.util.List;

/** Queries Via's protocol graph without maintaining a duplicated Conduit table. */
public final class ConduitViaSupport {
  private ConduitViaSupport() {}

  public static boolean knowsProtocol(int protocolNumber) {
    if (!ConduitViaBootstrap.available()) return false;
    ProtocolVersion version = ProtocolVersion.getProtocol(protocolNumber);
    return version != null && version.isKnown();
  }

  /**
   * True when Via can build a translation path between the ordered pair.
   * Same-version pairs are not "translated" here — callers treat those as DIRECT.
   */
  public static boolean supportsTranslation(int clientProtocol, int backendProtocol) {
    if (!ConduitViaBootstrap.available()) return false;
    if (clientProtocol == backendProtocol) return false;
    ProtocolVersion client = ProtocolVersion.getProtocol(clientProtocol);
    ProtocolVersion backend = ProtocolVersion.getProtocol(backendProtocol);
    if (client == null || backend == null || !client.isKnown() || !backend.isKnown()) {
      return false;
    }
    List<ProtocolPathEntry> path = Via.getManager().getProtocolManager().getProtocolPath(client, backend);
    return path != null && !path.isEmpty();
  }

  /**
   * The protocol number ViaVersion gives a release name, for a version Conduit has no catalog
   * entry of its own for. Via ships a new Minecraft release long before a Conduit release can, and
   * an operator who writes that release's name into conduit.toml means the version their players
   * are on, not a typo to refuse a start over.
   *
   * <p>Via's table is static, so this answers before the platform has been started -- which is
   * where it is needed, since the configuration is read first. Without Via on the class path at
   * all there is no answer and the caller reports the name as unknown.
   */
  public static java.util.OptionalInt protocolByName(String name) {
    if (name == null || name.isBlank()) return java.util.OptionalInt.empty();
    String token = name.strip();
    try {
      for (ProtocolVersion version : ProtocolVersion.getProtocols()) {
        if (!version.isKnown()) continue;
        if (token.equalsIgnoreCase(version.getName())) return java.util.OptionalInt.of(version.getVersion());
        for (String included : version.getIncludedVersions()) {
          if (token.equalsIgnoreCase(included)) return java.util.OptionalInt.of(version.getVersion());
        }
      }
    } catch (Throwable viaMissing) {
      return java.util.OptionalInt.empty();
    }
    return java.util.OptionalInt.empty();
  }

  /** Via's name for a protocol number, whether or not the platform has started. */
  public static java.util.Optional<String> knownName(int protocolNumber) {
    try {
      ProtocolVersion version = ProtocolVersion.getProtocol(protocolNumber);
      if (version == null || !version.isKnown()) return java.util.Optional.empty();
      return java.util.Optional.ofNullable(version.getName());
    } catch (Throwable viaMissing) {
      return java.util.Optional.empty();
    }
  }

  /**
   * The highest Minecraft protocol Via knows how to carry, which is what a bundled Via that has
   * updated itself makes newly reachable. Empty when translation is off or Via is not up yet.
   *
   * <p>Snapshots are left out: a server list that named a snapshot would be advertising a version
   * no released client has.
   */
  public static java.util.OptionalInt highestKnownProtocol() {
    if (!ConduitViaBootstrap.available()) return java.util.OptionalInt.empty();
    int highest = Integer.MIN_VALUE;
    for (ProtocolVersion version : ProtocolVersion.getProtocols()) {
      if (!version.isKnown()) continue;
      if (version.getVersionType() != com.viaversion.viaversion.api.protocol.version.VersionType.RELEASE) continue;
      highest = Math.max(highest, version.getVersion());
    }
    return highest == Integer.MIN_VALUE ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(highest);
  }

  /**
   * Via's own name for a protocol, which is the released name and, where several releases share a
   * number, the range they cover: protocol 5 is "1.7.6-1.7.10".
   */
  public static java.util.Optional<String> releaseName(int protocolNumber) {
    if (!ConduitViaBootstrap.available()) return java.util.Optional.empty();
    ProtocolVersion version = ProtocolVersion.getProtocol(protocolNumber);
    if (version == null || !version.isKnown()) return java.util.Optional.empty();
    return java.util.Optional.ofNullable(version.getName());
  }

  public static String describePath(int clientProtocol, int backendProtocol) {
    if (!supportsTranslation(clientProtocol, backendProtocol)) return "none";
    ProtocolVersion client = ProtocolVersion.getProtocol(clientProtocol);
    ProtocolVersion backend = ProtocolVersion.getProtocol(backendProtocol);
    List<ProtocolPathEntry> path = Via.getManager().getProtocolManager().getProtocolPath(client, backend);
    StringBuilder builder = new StringBuilder();
    for (ProtocolPathEntry entry : path) {
      if (builder.length() > 0) builder.append(" -> ");
      builder.append(entry.protocol().getClass().getSimpleName());
    }
    return builder.toString();
  }
}
