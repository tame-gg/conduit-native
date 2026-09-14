package gg.tame.conduit.modded;

/**
 * Parses and strips Forge/NeoForge handshake host address markers.
 * Markers are NUL-delimited suffixes on the handshake hostname — not magic offsets.
 */
public final class FmlAddressMarkers {
  public static final String FML1 = "\0FML\0";
  public static final String FML2 = "\0FML2\0";
  public static final String FML3 = "\0FML3\0";

  private FmlAddressMarkers() {}

  public enum MarkerKind { NONE, FML1, FML2, FML3 }

  public record ParsedHost(String cleanHost, MarkerKind marker, ModLoaderFamily family) {
    public boolean hasMarker() { return marker != MarkerKind.NONE; }
  }

  public static ParsedHost parse(String requestedHost) {
    if (requestedHost == null) throw new IllegalArgumentException("handshake host is null");
    if (requestedHost.indexOf('\0') < 0) {
      return new ParsedHost(requestedHost, MarkerKind.NONE, ModLoaderFamily.UNKNOWN);
    }
    // Prefer longer markers first so FML2/FML3 are not misread as FML1.
    if (endsWithMarker(requestedHost, FML3)) {
      return strip(requestedHost, FML3, MarkerKind.FML3, ModLoaderFamily.NEOFORGE);
    }
    if (endsWithMarker(requestedHost, FML2)) {
      return strip(requestedHost, FML2, MarkerKind.FML2, ModLoaderFamily.FORGE);
    }
    if (endsWithMarker(requestedHost, FML1)) {
      return strip(requestedHost, FML1, MarkerKind.FML1, ModLoaderFamily.FORGE);
    }
    throw new IllegalArgumentException("malformed FML address marker in handshake host");
  }

  public static String append(String cleanHost, MarkerKind marker) {
    if (cleanHost == null) throw new IllegalArgumentException("host is null");
    if (cleanHost.indexOf('\0') >= 0) throw new IllegalArgumentException("clean host must not contain NUL");
    return switch (marker) {
      case NONE -> cleanHost;
      case FML1 -> cleanHost + FML1;
      case FML2 -> cleanHost + FML2;
      case FML3 -> cleanHost + FML3;
    };
  }

  public static MarkerKind markerFor(ModLoaderFamily family, MarkerKind observed) {
    if (observed != MarkerKind.NONE) return observed;
    return switch (family) {
      case FORGE -> MarkerKind.FML2;
      case NEOFORGE -> MarkerKind.FML3;
      default -> MarkerKind.NONE;
    };
  }

  private static boolean endsWithMarker(String host, String marker) {
    return host.length() >= marker.length() && host.endsWith(marker);
  }

  private static ParsedHost strip(String host, String marker, MarkerKind kind, ModLoaderFamily family) {
    String clean = host.substring(0, host.length() - marker.length());
    if (clean.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("handshake host contains unexpected NUL data");
    }
    return new ParsedHost(clean, kind, family);
  }
}
