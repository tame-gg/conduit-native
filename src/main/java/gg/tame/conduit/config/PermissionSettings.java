// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.config;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code [permissions]} in {@code conduit.toml}: who runs Conduit's admin commands in-game while no
 * permissions plugin is installed.
 *
 * <p>{@code operators} are names or UUIDs. Each holds every {@code conduit.} node until a permissions
 * plugin such as LuckPerms takes over, and from then on that plugin decides alone. Names are matched
 * without regard to case. In offline mode a name is only what the client claims, so there a UUID is
 * the safer entry, and a name is a promise to whoever types it.
 */
public record PermissionSettings(Set<String> operators) {
  public PermissionSettings {
    operators = operators == null ? Set.of() : operators.stream()
        .map(entry -> entry.strip().toLowerCase(Locale.ROOT))
        .filter(entry -> !entry.isEmpty())
        .collect(Collectors.toUnmodifiableSet());
  }

  public static PermissionSettings defaults() { return new PermissionSettings(Set.of()); }

  /** Whether this name or account is an operator. */
  public boolean isOperator(String username, java.util.UUID account) {
    if (operators.isEmpty()) return false;
    if (username != null && operators.contains(username.toLowerCase(Locale.ROOT))) return true;
    return account != null && operators.contains(account.toString().toLowerCase(Locale.ROOT));
  }
}
