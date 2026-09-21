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
