// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.permission;

/**
 * Replaceable permission source. Until a plugin installs one, Conduit's default grants a player no
 * {@code conduit.} node and every other node.
 */
public interface PermissionProvider {
  boolean hasPermission(PermissionSubject subject, String permission);

  /**
   * Whether this provider holds permissions of its own for {@code subject}, rather than a blanket
   * answer. Conduit's default provider, which answers the same for everyone until a plugin sets one,
   * answers false, and so should a provider that hands {@code subject} on to it.
   *
   * <p>Conduit asks this before a check a blanket answer must not pass: a player is let through
   * maintenance mode by {@code conduit.maintenance.bypass} or {@code conduit.admin} only when the
   * provider in force manages them. Defaults to true.
   */
  default boolean manages(PermissionSubject subject) { return true; }
}
