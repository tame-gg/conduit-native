// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.config;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Client version gating. Distinct from protocol translation — a catalog entry may be known
 * without being allowed to join.
 */
public record VersionGateSettings(
    boolean enabled,
    Set<Integer> allowProtocols,
    OptionalInt minimumProtocol,
    OptionalInt maximumProtocol,
    String pingVersionName,
    String kickMessage,
    String kickMessageRange,
    boolean strictBackendMatch
) {
  public static final String DEFAULT_PING = "Conduit {versions}";
  public static final String DEFAULT_KICK = "This network only allows Minecraft {versions}.";
  public static final String DEFAULT_KICK_RANGE = "This network only allows Minecraft versions {versions}.";

  public VersionGateSettings {
    allowProtocols = allowProtocols == null ? Set.of() : Set.copyOf(allowProtocols);
    if (minimumProtocol == null) minimumProtocol = OptionalInt.empty();
    if (maximumProtocol == null) maximumProtocol = OptionalInt.empty();
    if (pingVersionName == null || pingVersionName.isBlank()) pingVersionName = DEFAULT_PING;
    if (kickMessage == null || kickMessage.isBlank()) kickMessage = DEFAULT_KICK;
    if (kickMessageRange == null || kickMessageRange.isBlank()) kickMessageRange = DEFAULT_KICK_RANGE;
    if (minimumProtocol.isPresent() && maximumProtocol.isPresent()
        && minimumProtocol.getAsInt() > maximumProtocol.getAsInt()) {
      throw new IllegalArgumentException("versions.minimum must be <= versions.maximum");
    }
  }

  public static VersionGateSettings defaults() {
    return new VersionGateSettings(false, Set.of(), OptionalInt.empty(), OptionalInt.empty(),
        DEFAULT_PING, DEFAULT_KICK, DEFAULT_KICK_RANGE, false);
  }

  public boolean hasConstraints() {
    return !allowProtocols.isEmpty() || minimumProtocol.isPresent() || maximumProtocol.isPresent();
  }

  public VersionGateSettings withAllow(List<Integer> protocols) {
    return new VersionGateSettings(enabled, new LinkedHashSet<>(protocols), minimumProtocol, maximumProtocol,
        pingVersionName, kickMessage, kickMessageRange, strictBackendMatch);
  }
}
