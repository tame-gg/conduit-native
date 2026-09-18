// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.permission;

public interface PermissionSubject {
  boolean hasPermission(String permission);
}
