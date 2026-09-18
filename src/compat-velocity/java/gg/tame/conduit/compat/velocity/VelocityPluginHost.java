// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.compat.velocity;

import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.plugin.meta.PluginDependency;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Velocity's view of the loaded Velocity plugins. Conduit's plugin manager owns their lifecycle;
 * this only answers the Velocity PluginManager questions about them.
 */
final class VelocityPluginHost implements PluginManager {
  private final ConcurrentHashMap<String, Container> byId = new ConcurrentHashMap<>();

  void add(Container container) { byId.put(container.description.getId(), container); }
  void remove(Container container) { byId.remove(container.description.getId(), container); }

  /** The container for a plugin instance, or a container passed as itself. */
  Optional<Container> find(Object plugin) {
    if (plugin instanceof Container container) return byId.get(container.description.getId()) == container ? Optional.of(container) : Optional.empty();
    if (plugin == null) return Optional.empty();
    for (Container container : byId.values()) if (container.instance == plugin) return Optional.of(container);
    return Optional.empty();
  }
  /**
   * The plugin whose jar {@code code}'s class came from, or null. A class loader a plugin made under
   * its own counts as the plugin's, as when a plugin loads its body from a jar inside its jar.
   */
  Container owning(Object code) { return code == null ? null : loadedBy(code.getClass().getClassLoader()); }
  /** The plugin whose jar a class with this loader, or a loader under the plugin's, came from; or null. */
  Container loadedBy(ClassLoader loader) {
    for (; loader != null; loader = loader.getParent()) {
      for (Container container : byId.values()) if (loader == container.loader) return container;
    }
    return null;
  }
  Container require(Object plugin) {
    return find(plugin).orElseThrow(() -> new IllegalArgumentException(
        (plugin == null ? "null" : plugin.getClass().getName()) + " is not a Velocity plugin loaded on this proxy"));
  }

  @Override public Optional<PluginContainer> fromInstance(Object instance) { return find(instance).map(PluginContainer.class::cast); }
  @Override public Optional<PluginContainer> getPlugin(String id) { return Optional.ofNullable(byId.get(id)); }
  @Override public Collection<PluginContainer> getPlugins() { return List.copyOf(byId.values()); }
  @Override public boolean isLoaded(String id) { return byId.containsKey(id); }
  @Override public void addToClasspath(Object plugin, Path path) {
    try { require(plugin).loader.addURL(path.toUri().toURL()); }
    catch (MalformedURLException bad) { throw new IllegalArgumentException("not a usable path: " + path, bad); }
  }

  record Description(String id, String name, String version, String description, String url, List<String> authors,
                     List<PluginDependency> dependencies, Path source, String main) implements PluginDescription {
    @Override public String getId() { return id; }
    @Override public Optional<String> getName() { return Optional.ofNullable(name); }
    @Override public Optional<String> getVersion() { return Optional.ofNullable(version); }
    @Override public Optional<String> getDescription() { return Optional.ofNullable(description); }
    @Override public Optional<String> getUrl() { return Optional.ofNullable(url); }
    @Override public List<String> getAuthors() { return authors; }
    @Override public Collection<PluginDependency> getDependencies() { return dependencies; }
    @Override public Optional<Path> getSource() { return Optional.ofNullable(source); }
  }

  static final class Container implements PluginContainer {
    final Description description;
    final VelocityClassLoader loader;
    final VelocityPluginHandle handle;
    volatile Object instance;
    /** Whether this plugin has had its ProxyShutdownEvent, which it gets exactly once. */
    volatile boolean shutdownDelivered;
    private volatile ExecutorService executor;
    Container(Description description, VelocityClassLoader loader, VelocityPluginHandle handle) {
      this.description = description; this.loader = loader; this.handle = handle;
    }
    String id() { return description.getId(); }
    @Override public PluginDescription getDescription() { return description; }
    @Override public Optional<?> getInstance() { return Optional.ofNullable(instance); }
    @Override public synchronized ExecutorService getExecutorService() {
      if (executor == null) {
        AtomicInteger threads = new AtomicInteger();
        executor = Executors.newCachedThreadPool(runnable -> {
          Thread thread = new Thread(runnable, "velocity-" + id() + "-" + threads.incrementAndGet());
          thread.setDaemon(true);
          return thread;
        });
      }
      return executor;
    }
    synchronized void shutdownExecutor() { if (executor != null) executor.shutdownNow(); }
    @Override public String toString() { return "Velocity plugin " + id(); }
  }
}
