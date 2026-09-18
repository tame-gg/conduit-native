// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.compat.velocity;

import com.velocitypowered.api.event.permission.PermissionsSetupEvent;
import com.velocitypowered.api.permission.PermissionFunction;
import com.velocitypowered.api.permission.Tristate;
import gg.tame.conduit.api.permission.PermissionProvider;
import gg.tame.conduit.api.permission.PermissionSubject;
import gg.tame.conduit.api.player.Player;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Velocity's per-player permission functions on Conduit's single permission provider.
 *
 * <p>Each joining player gets one PermissionsSetupEvent, from Conduit's PlayerSetupEvent: after
 * authentication and before anything is decided about them, maintenance included. Its default
 * provider answers what Conduit would. When a plugin sets its own, that function answers for the
 * player everywhere: to Velocity plugins directly, and to Conduit through a native provider installed
 * in that plugin's name, so Conduit drops it when the plugin is disabled. Conduit's answer is a
 * boolean, and UNDEFINED reads as false there, as {@code PermissionSubject.hasPermission} reads it.
 * What is kept for a player goes at their PlayerDisconnectEvent, which every set-up player gets.
 */
final class VelocityPermissions {
  /** A function, and the plugin whose code it is; no function means the event left the default. */
  private record Grant(PermissionFunction function, VelocityPluginHost.Container plugin) {}
  private static final Grant CONDUIT_DEFAULT = new Grant(null, null);

  private final VelocityEnvironment environment;
  private final ConcurrentHashMap<Player, Grant> grants = new ConcurrentHashMap<>();
  private final PermissionProvider provider = new PermissionProvider() {
    @Override public boolean hasPermission(PermissionSubject subject, String permission) { return check(subject, permission); }
    /**
     * A player with a plugin's function is that plugin's; anyone else is answered by Conduit's own
     * provider, and is managed only if that is. Otherwise Conduit's permissive default, reached
     * through here, would let everyone a plugin left alone through maintenance.
     */
    @Override public boolean manages(PermissionSubject subject) {
      Grant grant = subject instanceof Player player ? grants.get(player) : null;
      return grant != null && grant.function != null || before.manages(subject);
    }
  };
  /** Conduit's provider from before a Velocity plugin took over: what players on the default get. */
  private volatile PermissionProvider before;

  VelocityPermissions(VelocityEnvironment environment) { this.environment = environment; }

  /** Fires PermissionsSetupEvent for a player who is logging in, and keeps what it settles on. */
  void setUp(VelocityPlayer player) {
    if (!environment.events.listening(PermissionsSetupEvent.class)) return;
    Player nativePlayer = player.nativePlayer();
    com.velocitypowered.api.permission.PermissionProvider defaults =
        subject -> permission -> Tristate.fromBoolean(conduitDefault(nativePlayer, permission));
    // createFunction is plugin code too, so it runs on the adapter's threads with the handlers.
    CompletableFuture<Grant> settled = environment.events.fire(new PermissionsSetupEvent(player, defaults)).thenApplyAsync(event -> {
      if (event.getProvider() == defaults) return CONDUIT_DEFAULT;
      PermissionFunction function = event.createFunction(player);
      return function == null ? CONDUIT_DEFAULT : new Grant(function, environment.plugins.owning(event.getProvider()));
    }, environment.work);
    Grant grant = environment.await(settled, "PermissionsSetupEvent") ? settled.join() : CONDUIT_DEFAULT;
    synchronized (this) {
      // The function is kept only in the name of a plugin that is still here, since that plugin's
      // disable is what takes it away again. One disabled while this player was being set up has had
      // its grants taken back already; kept now, its function would outlive it for good.
      if (grant.function != null && (grant.plugin == null || environment.plugins.find(grant.plugin).isEmpty())) {
        environment.log.warning("A permission function for " + nativePlayer.username() + " came from no enabled Velocity plugin; "
            + "Conduit's permissions apply to them instead");
        grant = CONDUIT_DEFAULT;
      }
      grants.put(nativePlayer, grant);
      if (grant.function != null) install(grant.plugin);
    }
  }

  /** The player's Velocity function, or null when Conduit's own answer stands. */
  PermissionFunction function(Player player) {
    Grant grant = grants.get(player);
    return grant == null ? null : grant.function;
  }

  void forget(Player player) { grants.remove(player); }

  /** A disabled plugin's functions stop answering; its players are back on Conduit's default. */
  synchronized void release(VelocityPluginHost.Container plugin) {
    grants.replaceAll((player, grant) -> grant.plugin == plugin ? CONDUIT_DEFAULT : grant);
  }

  private void install(VelocityPluginHost.Container plugin) {
    PermissionProvider current = environment.conduit.permissions();
    if (current == provider) return;
    before = current;
    environment.conduit.setPermissionProvider(plugin.handle, provider);
  }

  /** Conduit asking, often on a player's connection thread: the plugin's function answers on the adapter's. */
  private boolean check(PermissionSubject subject, String permission) {
    if (!(subject instanceof Player player)) return before.hasPermission(subject, permission);
    Grant grant = grants.get(player);
    if (grant == null) {
      // Not set up: asked before PlayerSetupEvent reached the adapter, or after the player left. While
      // a plugin's functions are in force, such a player has no permissions, rather than all the
      // default grants.
      boolean online = environment.conduit.player(player.uniqueId()).filter(live -> live == player).isPresent();
      return online && before.hasPermission(subject, permission);
    }
    if (grant.function == null) return before.hasPermission(subject, permission);
    CompletableFuture<Tristate> answer = CompletableFuture.supplyAsync(() -> grant.function.getPermissionValue(permission), environment.work);
    return environment.await(answer, "a permission check for " + permission) && answer.join().asBoolean();
  }

  /** What Conduit answers without any Velocity function: its provider, or the one this displaced. */
  private boolean conduitDefault(Player player, String permission) {
    PermissionProvider current = environment.conduit.permissions();
    try { return (current == provider ? before : current).hasPermission(player, permission); }
    catch (RuntimeException | LinkageError failed) {
      // As Conduit treats a provider that throws: the permission is denied.
      environment.log.log(java.util.logging.Level.WARNING, "permission provider failed on " + permission, failed);
      return false;
    }
  }
}
