// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.modded;

import gg.tame.conduit.config.BackendServer;
import java.util.EnumSet;
import java.util.Set;

/** Compatibility and routing helpers for mod-loader families. */
public final class ModCompatibility {
  private ModCompatibility() {}

  public static CompatibilityDecision decide(ModLoaderFamily client, Set<ModLoaderFamily> backendSupports) {
    if (backendSupports == null || backendSupports.isEmpty()) {
      // Unconfigured backend accepts all families (vanilla-default friendly).
      return CompatibilityDecision.COMPATIBLE;
    }
    if (client == ModLoaderFamily.UNKNOWN) {
      if (backendSupports.contains(ModLoaderFamily.UNKNOWN)) return CompatibilityDecision.COMPATIBLE;
      return CompatibilityDecision.UNKNOWN;
    }
    if (backendSupports.contains(client)) return CompatibilityDecision.COMPATIBLE;
    // NeoForge clients often speak Forge-compatible handshake markers; allow when backend lists FORGE only if NEOFORGE also listed? No — explicit.
    // Forge backend may accept NeoForge if operator listed both.
    return CompatibilityDecision.INCOMPATIBLE;
  }

  public static boolean isEligible(ModLoaderFamily client, BackendServer server, UnknownModdedPolicy unknownPolicy) {
    Set<ModLoaderFamily> supported = server.supportedModLoaders();
    CompatibilityDecision decision = decide(client, supported);
    if (decision == CompatibilityDecision.COMPATIBLE) return true;
    if (decision == CompatibilityDecision.INCOMPATIBLE) return false;
    // UNKNOWN decision (unknown client vs restricted backend)
    return switch (unknownPolicy) {
      case ALLOW -> true;
      case DENY, ROUTE_TO_FALLBACK -> false;
    };
  }

  public static Set<ModLoaderFamily> parseList(java.util.List<String> raw) {
    if (raw == null || raw.isEmpty()) return Set.of();
    EnumSet<ModLoaderFamily> set = EnumSet.noneOf(ModLoaderFamily.class);
    for (String entry : raw) set.add(ModLoaderFamily.parse(entry));
    return Set.copyOf(set);
  }
}
