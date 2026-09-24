// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.routing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Hostname to backend: {@code [forced-hosts]} in conduit.toml, so lobby.example.com and
 * pvp.example.com land on different servers through one proxy and one port.
 *
 * <p>The host matched against is the one the client wrote in its handshake, which is whatever its
 * server list entry says and never anything the proxy resolved. It is matched on the name alone:
 * the port the entry named is dropped, an FML/Forge marker is dropped, a trailing root dot is
 * dropped, and the comparison ignores case, because all of those are the same host to a player who
 * typed it once.
 *
 * <p>A host nothing matches is not an error and not a refusal: the player follows
 * {@code routing.initial} as they always have.
 */
public final class ForcedHosts {
  private static final ForcedHosts NONE = new ForcedHosts(Map.of());

  private final Map<String, List<String>> byHost;

  private ForcedHosts(Map<String, List<String>> byHost) { this.byHost = byHost; }

  public static ForcedHosts none() { return NONE; }

  /**
   * {@code hosts} as written in the file: each key a hostname, each value the servers to try for it
   * in order. Hosts that differ only in case or in a trailing dot are the same host, and the later
   * one is refused rather than silently winning.
   */
  public static ForcedHosts of(Map<String, List<String>> hosts) {
    if (hosts == null || hosts.isEmpty()) return NONE;
    Map<String, List<String>> normalized = new LinkedHashMap<>();
    Map<String, String> written = new LinkedHashMap<>();
    for (Map.Entry<String, List<String>> entry : hosts.entrySet()) {
      String host = normalize(entry.getKey());
      if (host.isEmpty()) throw new IllegalArgumentException("forced-hosts has an empty hostname");
      if (entry.getValue() == null || entry.getValue().isEmpty()) {
        throw new IllegalArgumentException("forced-hosts." + entry.getKey() + " must name at least one server");
      }
      String earlier = written.putIfAbsent(host, entry.getKey());
      if (earlier != null) {
        throw new IllegalArgumentException("forced-hosts." + entry.getKey() + " is the same host as forced-hosts."
            + earlier + " (hostnames ignore case)");
      }
      normalized.put(host, List.copyOf(entry.getValue()));
    }
    return new ForcedHosts(Map.copyOf(normalized));
  }

  public boolean isEmpty() { return byHost.isEmpty(); }

  /** Every host configured, normalized, for a caller checking the servers they name. */
  public Map<String, List<String>> all() { return byHost; }

  /**
   * The servers {@code requestedHost} forces, in order, or an empty list for a host that forces
   * nothing. {@code requestedHost} is the raw handshake address, markers and port and all.
   */
  public List<String> match(String requestedHost) {
    if (byHost.isEmpty() || requestedHost == null) return List.of();
    return byHost.getOrDefault(normalize(clean(requestedHost)), List.of());
  }

  /** The handshake address without its FML/Forge marker; an unparseable marker leaves it alone. */
  private static String clean(String requestedHost) {
    try {
      return gg.tame.conduit.modded.FmlAddressMarkers.parse(requestedHost).cleanHost();
    } catch (IllegalArgumentException malformed) {
      int nul = requestedHost.indexOf('\0');
      return nul < 0 ? requestedHost : requestedHost.substring(0, nul);
    }
  }

  /** Case, the trailing root dot, surrounding space and any {@code :port} all dropped. */
  /** Shared with per-host status, which is keyed by the same form. */
  public static String normalize(String host) {
    String text = host == null ? "" : host.strip();
    // A bracketed IPv6 literal carries colons of its own, so only the port after the brackets goes.
    if (text.startsWith("[")) {
      int close = text.indexOf(']');
      if (close >= 0) text = text.substring(0, close + 1);
    } else {
      int colon = text.indexOf(':');
      // An unbracketed IPv6 literal has several colons and no port; one colon is a port.
      if (colon >= 0 && text.indexOf(':', colon + 1) < 0) text = text.substring(0, colon);
    }
    while (text.endsWith(".")) text = text.substring(0, text.length() - 1);
    return text.toLowerCase(Locale.ROOT);
  }

  @Override public boolean equals(Object other) {
    return other instanceof ForcedHosts forced && byHost.equals(forced.byHost);
  }

  @Override public int hashCode() { return byHost.hashCode(); }

  @Override public String toString() {
    List<String> entries = new ArrayList<>();
    byHost.forEach((host, servers) -> entries.add(host + "=" + servers));
    return "ForcedHosts" + entries;
  }
}
