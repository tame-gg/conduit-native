// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.permission;

/** Replaceable permission source. Default is permissive. */
public interface PermissionProvider {
  boolean hasPermission(PermissionSubject subject, String permission);

  /**
   * Whether this provider holds permissions of its own for {@code subject}, rather than a blanket
   * answer. Conduit's default provider, which grants every node until a plugin sets one, answers
   * false, and so should a provider that hands {@code subject} on to it.
   *
   * <p>Conduit asks this before a check the permissive default must not pass: a player is let
   * through maintenance mode by {@code conduit.maintenance.bypass} or {@code conduit.admin} only
   * when the provider in force manages them, or a default that grants everything would let everyone
   * in. Defaults to true.
   */
  default boolean manages(PermissionSubject subject) { return true; }
}
