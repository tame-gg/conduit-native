package gg.tame.conduit.modded;

import java.util.Locale;

/** Behavior when the client family is UNKNOWN. */
public enum UnknownModdedPolicy {
  ALLOW,
  DENY,
  ROUTE_TO_FALLBACK;

  public static UnknownModdedPolicy parse(String raw) {
    if (raw == null || raw.isBlank()) return ALLOW;
    return switch (raw.strip().toLowerCase(Locale.ROOT).replace('-', '_')) {
      case "allow" -> ALLOW;
      case "deny" -> DENY;
      case "route_to_fallback", "fallback" -> ROUTE_TO_FALLBACK;
      default -> throw new IllegalArgumentException("unknown-modded-policy must be allow, deny, or route_to_fallback");
    };
  }
}
