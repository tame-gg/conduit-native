// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.permission;

import gg.tame.conduit.api.permission.PermissionProvider;
import gg.tame.conduit.api.permission.PermissionSubject;

/** Default provider: allow every node. Replace to add a real permissions plugin. */
public final class PermissivePermissionProvider implements PermissionProvider {
  @Override public boolean hasPermission(PermissionSubject subject, String permission) { return true; }
  /** Nobody's permissions are really known here, so nobody gets through maintenance on its say-so. */
  @Override public boolean manages(PermissionSubject subject) { return false; }
}
