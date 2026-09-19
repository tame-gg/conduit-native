// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.permission;

/**
 * Replaceable permission source. Until a plugin installs one, Conduit's default grants a player
 * nothing; the console holds every node regardless of the provider.
 */
public interface PermissionProvider {
  boolean hasPermission(PermissionSubject subject, String permission);

  /**
   * The node's value where a denial can be told apart from silence: {@code TRUE} granted,
   * {@code FALSE} denied outright, {@code null} not mentioned. Conduit asks this rather than
   * {@link #hasPermission} wherever {@code conduit.admin} would otherwise stand in, so a player
   * given admin and an explicit {@code false} on one node is refused that one.
   *
   * <p>A provider that answers only yes and no leaves this alone: its yes is a grant and its no is
   * silence, which is how a boolean provider behaved before there was anything else.
   */
  default Boolean permissionValue(PermissionSubject subject, String permission) {
    return hasPermission(subject, permission) ? Boolean.TRUE : null;
  }

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
