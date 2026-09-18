// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.plugin;

import java.util.Collection;
import java.util.Optional;

/**
 * Enabled plugins, of every format.
 *
 * <p>Lifecycle: at startup plugins are enabled in dependency order ({@code onLoad}, then
 * {@code onEnable}, then {@code PluginEnableEvent}). A plugin whose {@code onLoad} or
 * {@code onEnable} throws is not enabled, loses everything it registered, and takes every plugin that
 * depends on it with it. At shutdown plugins are disabled in reverse order, dependents first.
 */
public interface PluginManager {
  Collection<Plugin> plugins();
  Optional<Plugin> plugin(String id);

  /**
   * Disables {@code plugin}, and first every enabled plugin that depends on it: fires
   * {@code PluginDisableEvent}, calls {@code onDisable}, then unregisters its listeners, commands
   * and scheduled tasks. Disabling a plugin that is not enabled does nothing.
   */
  void disable(Plugin plugin);

  /**
   * Adds a plugin format, offered every jar before Conduit reads it as a native plugin.
   *
   * <p>Only possible before plugins load, which in practice means from the proxy's bootstrap: when
   * the class {@code gg.tame.conduit.compat.velocity.VelocityBoot} is on the proxy's classpath,
   * Conduit calls its {@code public static void install(gg.tame.conduit.api.ConduitProxy)} while it
   * starts up, and that is where the Velocity layer registers its loader. A plugin's own
   * {@code onEnable} is too late, because every jar has been read by then.
   *
   * @throws IllegalStateException once plugins have been loaded
   */
  void registerLoader(PluginLoader loader);
}
