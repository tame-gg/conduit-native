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
