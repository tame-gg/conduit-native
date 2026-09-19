// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.permission;

import gg.tame.conduit.api.permission.PermissionProvider;
import gg.tame.conduit.api.permission.PermissionSubject;
import java.util.Locale;

/**
 * What answers until a permissions plugin installs a provider.
 *
 * <p>It used to grant every node, so with no permissions plugin any player could reload the proxy,
 * kick players or turn maintenance on. It still grants every node except Conduit's own: those, the
 * {@code conduit.} nodes, are granted to nobody, and the console, which holds every node regardless,
 * is how an operator administers a proxy with no permissions plugin. The commands every player may use
 * ({@code /server} and its shortcuts, {@code /hub}, {@code /ping}) carry no node, so they are unaffected.
 *
 * <p>Other plugins' nodes are still granted, as before.
 * ponytail: a plugin's own administrative nodes are therefore open to every player until a permissions
 * plugin is installed; deny those too if that default ever has to be safe for more than Conduit.
 */
public final class DefaultPermissionProvider implements PermissionProvider {
  @Override public boolean hasPermission(PermissionSubject subject, String permission) {
    return permission == null || !permission.toLowerCase(Locale.ROOT).startsWith("conduit.");
  }
  /** Nobody's permissions are really known here, so nobody gets through maintenance on its say-so. */
  @Override public boolean manages(PermissionSubject subject) { return false; }
}
