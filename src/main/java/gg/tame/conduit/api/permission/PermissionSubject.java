// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.permission;

public interface PermissionSubject {
  boolean hasPermission(String permission);

  /**
   * The node's value where it can be told apart from silence: {@code TRUE} granted, {@code FALSE}
   * denied outright, {@code null} not mentioned. Only an explicit {@code FALSE} overrides
   * {@code conduit.admin}; a node nobody mentioned still falls back to it.
   *
   * <p>A subject that knows only yes and no -- the console, a test double -- leaves this alone: its
   * yes is a grant and its no is silence, which is how a boolean answer behaved before.
   */
  default Boolean permissionValue(String permission) { return hasPermission(permission) ? Boolean.TRUE : null; }
}
