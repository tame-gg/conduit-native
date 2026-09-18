// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.modded;

/**
 * Parses and strips Forge/NeoForge handshake host address markers.
 * Markers are NUL-delimited suffixes on the handshake hostname — not magic offsets.
 */
public final class FmlAddressMarkers {
  public static final String FML1 = "\0FML\0";
  public static final String FML2 = "\0FML2\0";
  public static final String FML3 = "\0FML3\0";
  /** Forge and NeoForge 1.20.2+, which replaced FML2/FML3 with this token. */
  public static final String FORGE = "\0FORGE\0";

  private FmlAddressMarkers() {}

  public enum MarkerKind { NONE, FML1, FML2, FML3, FORGE }

  public record ParsedHost(String cleanHost, MarkerKind marker, ModLoaderFamily family) {
    public boolean hasMarker() { return marker != MarkerKind.NONE; }
  }

  /**
   * Splits a handshake host into its address and its mod-loader token.
   *
   * <p>The trailing NUL is optional, and that is not a nicety. A real NeoForge 20.2.93 client
   * sends {@code "127.0.0.1\0FML3"} — leading NUL, token, nothing after it. Matching only the
   * doubly-delimited {@code "\0FML3\0"} the wiki describes made every such handshake malformed,
   * and Conduit dropped the socket without writing anything back.
   *
   * <p>No token identifies NeoForge: 20.2.93 sends the same FML3 a Forge client of that era does.
   * The token classifies Forge and the client's brand promotes it to NeoForge, never the reverse.
   */
  public static ParsedHost parse(String requestedHost) {
    if (requestedHost == null) throw new IllegalArgumentException("handshake host is null");
    int first = requestedHost.indexOf('\0');
    if (first < 0) return new ParsedHost(requestedHost, MarkerKind.NONE, ModLoaderFamily.UNKNOWN);
    String[] parts = requestedHost.substring(first + 1).split("\0", -1);
    MarkerKind marker = tokenKind(parts[0]);
    if (marker == MarkerKind.NONE) throw new IllegalArgumentException("malformed FML address marker in handshake host");
    for (int i = 1; i < parts.length; i++) {
      if (!parts[i].isEmpty()) throw new IllegalArgumentException("handshake host contains unexpected NUL data");
    }
    return new ParsedHost(requestedHost.substring(0, first), marker, ModLoaderFamily.FORGE);
  }

  private static MarkerKind tokenKind(String token) {
    return switch (token) {
      case "FML" -> MarkerKind.FML1;
      case "FML2" -> MarkerKind.FML2;
      case "FML3" -> MarkerKind.FML3;
      case "FORGE" -> MarkerKind.FORGE;
      default -> MarkerKind.NONE;
    };
  }

  public static String append(String cleanHost, MarkerKind marker) {
    if (cleanHost == null) throw new IllegalArgumentException("host is null");
    if (cleanHost.indexOf('\0') >= 0) throw new IllegalArgumentException("clean host must not contain NUL");
    return switch (marker) {
      case NONE -> cleanHost;
      case FML1 -> cleanHost + FML1;
      case FML2 -> cleanHost + FML2;
      case FML3 -> cleanHost + FML3;
      case FORGE -> cleanHost + FORGE;
    };
  }

  /**
   * The marker to put on a backend handshake. Only ever the one the client actually sent: which
   * token a loader uses is a property of the client's version, not of its family, and a client
   * that sent none is telling the backend it speaks no FML handshake at all.
   */
  public static MarkerKind markerFor(ModLoaderFamily family, MarkerKind observed) {
    return observed;
  }

}
