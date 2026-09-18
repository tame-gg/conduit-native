// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.plugin;

import java.nio.file.Path;
import java.util.jar.JarFile;

/**
 * Reads a plugin format other than Conduit's own {@code conduit-plugin.yml} jar.
 *
 * <p>This is how the Velocity compatibility layer plugs in, and the only thing it needs besides the
 * rest of this API. Register a loader with {@link PluginManager#registerLoader} before plugins are
 * loaded; the proxy's bootstrap is the place for it (see {@link PluginManager#registerLoader}).
 *
 * <p>At startup every jar in the plugins directory is offered to each loader in registration order,
 * and read as a native plugin only when none claims it. What {@link #load} returns is then treated
 * exactly like a native plugin: ordered by its dependencies, attached (data directory, logger,
 * scheduler), {@code onLoad} and {@code onEnable} called, listed in {@code /plugins} under this
 * loader's {@link #format}, and on disable or shutdown given {@code onDisable} and stripped of every
 * listener, command and task registered under it. The loader never runs any of that itself.
 *
 * <p>Called on the thread that loads plugins, before the proxy accepts connections.
 */
public interface PluginLoader {
  /** Short lowercase tag for this format, shown beside its plugins in {@code /plugins}, e.g. {@code velocity}. */
  String format();

  /** Whether this loader claims {@code jar}. Only read what is needed to decide; do not keep the jar. */
  boolean accepts(JarFile jar);

  /**
   * Reads {@code jar} and returns its plugin, not yet enabled. Throwing rejects the jar, and the
   * loader must close whatever it opened before it throws.
   */
  Loaded load(Path jar) throws Exception;

  /**
   * One plugin read from a jar.
   *
   * <p>{@code resources} is closed exactly once: after the plugin's {@code onDisable}, or as soon as
   * Conduit gives up on it (a duplicate id, a missing dependency, an {@code onLoad} or
   * {@code onEnable} that threw). Pass the jar's class loader here: an open one keeps the jar locked
   * on Windows, and the operator cannot replace it without restarting the proxy.
   */
  record Loaded(PluginDescription description, ConduitPlugin plugin, AutoCloseable resources) {
    public Loaded {
      if (description == null || plugin == null) throw new IllegalArgumentException("description and plugin are required");
      if (resources == null) resources = () -> { };
    }
  }
}
