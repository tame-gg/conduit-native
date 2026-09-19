// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.permission;

import gg.tame.conduit.api.permission.PermissionProvider;
import gg.tame.conduit.api.permission.PermissionSubject;

/**
 * What answers until a permissions plugin installs a provider: nothing is granted to a player.
 *
 * <p>It used to grant every node, so with no permissions plugin any player could reload the proxy,
 * kick players or turn maintenance on. Denying Conduit's own {@code conduit.} nodes fixed that for
 * Conduit and left every other plugin's administrative node -- Maintenance's, LuckPerms' own --
 * open to everyone, which is the same hole one plugin further out. A proxy with no permissions
 * plugin now grants a player no node at all, which is what a Velocity plugin already expects of an
 * unanswered check, and the console, which holds every node regardless, is how an operator
 * administers it. The commands every player may use ({@code /server} and its shortcuts,
 * {@code /hub}, {@code /ping}) carry no node, so they are unaffected.
 */
public final class DefaultPermissionProvider implements PermissionProvider {
  @Override public boolean hasPermission(PermissionSubject subject, String permission) { return false; }
  /** Nobody's permissions are really known here, so nobody gets through maintenance on its say-so. */
  @Override public boolean manages(PermissionSubject subject) { return false; }
}
