// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.config;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Network-wide maintenance mode configuration. Runtime toggle may override active-on-start. */
public record MaintenanceSettings(
    boolean featureEnabled,
    boolean activeOnStart,
    String kickMessage,
    String motd,
    Set<String> allowlist
) {
  public static final String DEFAULT_KICK = "This network is currently under maintenance. Please try again later.";
  public static final String DEFAULT_MOTD = "Conduit — under maintenance";

  public MaintenanceSettings {
    if (kickMessage == null || kickMessage.isBlank()) kickMessage = DEFAULT_KICK;
    if (motd == null || motd.isBlank()) motd = DEFAULT_MOTD;
    allowlist = allowlist == null ? Set.of() : Set.copyOf(allowlist.stream()
        .map(name -> name.toLowerCase(Locale.ROOT))
        .collect(Collectors.toSet()));
  }

  public static MaintenanceSettings defaults() {
    return new MaintenanceSettings(true, false, DEFAULT_KICK, DEFAULT_MOTD, Set.of());
  }

  public boolean allowsUsername(String username) {
    if (username == null || username.isBlank()) return false;
    return allowlist.contains(username.toLowerCase(Locale.ROOT));
  }

  public MaintenanceSettings withAllowlist(List<String> names) {
    return new MaintenanceSettings(featureEnabled, activeOnStart, kickMessage, motd, Set.copyOf(names));
  }
}
