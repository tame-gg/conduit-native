// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.plugin;

import gg.tame.conduit.Conduit;
import gg.tame.conduit.api.ConduitProxy;
import gg.tame.conduit.api.event.plugin.PluginDisableEvent;
import gg.tame.conduit.api.event.plugin.PluginEnableEvent;
import gg.tame.conduit.api.plugin.ConduitPlugin;
import gg.tame.conduit.api.plugin.Plugin;
import gg.tame.conduit.api.plugin.PluginDescription;
import gg.tame.conduit.api.plugin.PluginLoader;
import gg.tame.conduit.api.plugin.PluginManager;
import gg.tame.conduit.command.CommandManager;
import gg.tame.conduit.event.ConduitEventManager;
import gg.tame.conduit.log.ConduitLog;
import gg.tame.conduit.runtime.ConduitRuntime;
import gg.tame.conduit.scheduler.ConduitScheduler;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarFile;
import java.util.logging.Logger;

public final class ConduitPluginManager implements PluginManager {
  /** Enable order. Guarded by itself; plugin code is never called while holding it. */
  private final Map<String, LoadedPlugin> plugins = new LinkedHashMap<>();
  private final Path pluginsDirectory;
  private final ConduitProxy proxy;
  private final ConduitEventManager events;
  private final ConduitScheduler scheduler;
  private final CommandManager commands;
  private final List<PluginLoader> loaders = new CopyOnWriteArrayList<>();
  private volatile boolean loaded;
  public ConduitPluginManager(Path pluginsDirectory, ConduitProxy proxy, ConduitEventManager events, ConduitScheduler scheduler, CommandManager commands) {
    this.pluginsDirectory = pluginsDirectory;
    this.proxy = proxy;
    this.events = events;
    this.scheduler = scheduler;
    this.commands = commands;
  }
  @Override public void registerLoader(PluginLoader loader) {
    if (loader == null) throw new IllegalArgumentException("loader is required");
    if (loaded) throw new IllegalStateException("plugins are already loaded; register loaders from the proxy bootstrap");
    loaders.add(loader);
  }
  /**
   * Reads and enables every jar in the plugins directory. Runs once: serve() calls it, and so do
   * tests and embedders that drive a runtime without serving. A second call enabled every plugin a
   * second time over the first, whose listeners, commands and class loader nothing could then reach.
   */
  public synchronized void loadAll() throws IOException {
    if (loaded) return;
    loaded = true;
    Files.createDirectories(pluginsDirectory);
    List<Path> jars = new ArrayList<>();
    try (var stream = Files.list(pluginsDirectory)) {
      stream.filter(path -> path.getFileName().toString().endsWith(".jar")).sorted().forEach(jars::add);
    }
    List<Pending> pending = new ArrayList<>();
    for (Path jar : jars) {
      try {
        Path normalized = jar.toAbsolutePath().normalize();
        if (!normalized.startsWith(pluginsDirectory.toAbsolutePath().normalize())) throw new IOException("plugin path escaped plugins directory");
        PluginLoader loader = claim(normalized);
        pending.add(loader != null ? foreign(loader, normalized) : read(normalized));
      }
      // LinkageError too: a main class whose static initializer throws, or whose supertype is not in
      // the jar, comes out of Class.forName as an Error. One such jar used to abort startup.
      catch (Exception | LinkageError rejected) { ConduitLog.error("rejected plugin jar " + jar.getFileName() + ": " + rejected); }
    }
    for (Pending item : resolve(pending)) enable(item);
  }
  /** The format loader that claims the jar, or null for a native plugin. Closes the jar before any loader opens it. */
  private PluginLoader claim(Path jar) throws IOException {
    try (JarFile file = new JarFile(jar.toFile())) {
      for (PluginLoader loader : loaders) if (loader.accepts(file)) return loader;
    }
    return null;
  }
  private Pending foreign(PluginLoader loader, Path jar) throws Exception {
    PluginLoader.Loaded result = loader.load(jar);
    if (result == null) throw new IOException(loader.format() + " loader returned nothing");
    return new Pending(result.description(), result.plugin(), result.resources(), loader.format());
  }
  private Pending read(Path normalized) throws Exception {
    try (JarFile file = new JarFile(normalized.toFile())) {
      var entry = file.getEntry("conduit-plugin.yml");
      if (entry == null) throw new IOException("missing conduit-plugin.yml");
      PluginDescription description;
      try (var input = file.getInputStream(entry)) { description = PluginDescriptorParser.parse(input); }
      if (description.apiVersion() > Conduit.API_VERSION) {
        throw new IOException("plugin " + description.id() + " requires API " + description.apiVersion() + " (proxy is " + Conduit.API_VERSION + ")");
      }
      URLClassLoader loader = new URLClassLoader(new URL[] { normalized.toUri().toURL() }, ConduitPlugin.class.getClassLoader());
      try {
        Class<?> type = Class.forName(description.mainClass(), true, loader);
        if (!ConduitPlugin.class.isAssignableFrom(type)) throw new IOException("main class must extend ConduitPlugin");
        ConduitPlugin plugin = (ConduitPlugin) type.getDeclaredConstructor().newInstance();
        return new Pending(description, plugin, loader, PluginCatalog.NATIVE);
      } catch (Exception | LinkageError failed) {
        // The loader holds the jar open, so a rejected plugin that kept one left the file locked
        // on Windows: the operator could not delete or replace the jar without restarting.
        close(loader);
        throw failed;
      }
    }
  }
  private List<Pending> resolve(List<Pending> pending) {
    Map<String, Pending> byId = new LinkedHashMap<>();
    for (Pending item : pending) {
      if (byId.putIfAbsent(item.description.id(), item) != null) {
        ConduitLog.error("duplicate plugin id " + item.description.id());
        close(item.resources);
      }
    }
    List<Pending> ordered = new ArrayList<>();
    Set<String> placed = new HashSet<>();
    List<Pending> remaining = new ArrayList<>(byId.values());
    while (!remaining.isEmpty()) {
      Pending next = pick(remaining, placed, byId.keySet(), true);
      // An optional dependency only orders. In a cycle through one it gives way, rather than
      // leaving every plugin in the cycle unloaded.
      if (next == null) next = pick(remaining, placed, byId.keySet(), false);
      if (next == null) {
        // One line listing every plugin left over said nothing about why: a dependency that was
        // never installed and a cycle between two plugins read the same. Each plugin now says which.
        for (Pending dropped : remaining) {
          ConduitLog.error("plugin " + dropped.description.id() + " was not loaded: " + unresolved(dropped, remaining, byId.keySet()));
          close(dropped.resources);
        }
        break;
      }
      remaining.remove(next);
      ordered.add(next);
      placed.add(next.description.id());
    }
    return ordered;
  }
  /** Why a plugin could not be placed: a dependency not installed, a cycle, or one that waits on those. */
  private String unresolved(Pending plugin, List<Pending> remaining, Set<String> present) {
    List<String> missing = plugin.description.dependencies().stream().filter(dep -> !present.contains(dep) && !enabled(dep)).toList();
    if (!missing.isEmpty()) return "it depends on " + String.join(", ", missing) + ", which " + (missing.size() == 1 ? "is" : "are") + " not installed";
    Map<String, Pending> left = new LinkedHashMap<>();
    for (Pending item : remaining) left.put(item.description.id(), item);
    List<String> cycle = cycleThrough(plugin.description.id(), plugin.description.id(), left, new ArrayList<>(), new HashSet<>());
    if (cycle != null) return "its dependencies form a cycle: " + String.join(" -> ", cycle);
    return "it depends on " + String.join(", ", plugin.description.dependencies().stream().filter(left::containsKey).toList())
        + ", which could not be loaded either";
  }
  /** The path of hard dependencies from {@code at} back to {@code start}, or null when there is none. */
  private static List<String> cycleThrough(String start, String at, Map<String, Pending> left, List<String> path, Set<String> seen) {
    path.add(at);
    for (String dep : left.get(at).description.dependencies()) {
      if (dep.equals(start)) { path.add(start); return path; }
      if (left.containsKey(dep) && seen.add(dep)) {
        List<String> found = cycleThrough(start, dep, left, path, seen);
        if (found != null) return found;
      }
    }
    path.removeLast();
    return null;
  }
  private Pending pick(List<Pending> remaining, Set<String> placed, Set<String> present, boolean strict) {
    for (Pending item : remaining) {
      boolean hard = item.description.dependencies().stream().allMatch(dep -> placed.contains(dep) || enabled(dep));
      boolean optional = !strict || item.description.optionalDependencies().stream()
          .allMatch(dep -> placed.contains(dep) || !present.contains(dep));
      if (hard && optional) return item;
    }
    return null;
  }
  private boolean enabled(String id) { synchronized (plugins) { return plugins.containsKey(id); } }
  private void enable(Pending pending) {
    String id = pending.description.id();
    try {
      // Ordering only promises a dependency was enabled before; it does not promise the enable
      // worked. A plugin whose dependency threw in onEnable was enabled anyway, against nothing.
      for (String dependency : pending.description.dependencies()) {
        if (!enabled(dependency)) throw new IOException("dependency " + dependency + " is not enabled");
      }
      Path root = pluginsDirectory.toAbsolutePath().normalize();
      Path data = root.resolve(id).normalize();
      if (!data.startsWith(root)) throw new IOException("plugin data path escaped plugins directory");
      Files.createDirectories(data);
      Logger logger = Logger.getLogger("plugin." + id);
      pending.plugin.attach(pending.description, proxy, logger, data, scheduler);
      pending.plugin.onLoad();
      pending.plugin.onEnable();
      synchronized (plugins) { plugins.put(id, new LoadedPlugin(pending.plugin, pending.resources)); }
      if (proxy instanceof ConduitRuntime runtime) {
        runtime.pluginCatalog().put(new PluginCatalog.Entry(id, pending.description.name(), pending.description.version(), pending.format));
      }
      events.fire(new PluginEnableEvent(pending.plugin));
      ConduitLog.info("Enabled " + (pending.format.equals(PluginCatalog.NATIVE) ? "" : pending.format + " ") + "plugin " + id + " " + pending.description.version());
    } catch (Exception | LinkageError failure) {
      ConduitLog.error("failed to enable plugin " + id, failure);
      // It never reached the plugins map, so disable() will never run for it. Anything it managed
      // to register before it threw would otherwise stay live with no owner able to take it back.
      synchronized (plugins) { plugins.remove(id); }
      if (proxy instanceof ConduitRuntime runtime) runtime.pluginCatalog().remove(id);
      release(pending.plugin);
      close(pending.resources);
    }
  }
  @Override public Collection<Plugin> plugins() {
    synchronized (plugins) { return plugins.values().stream().map(LoadedPlugin::plugin).toList(); }
  }
  @Override public Optional<Plugin> plugin(String id) {
    synchronized (plugins) { return Optional.ofNullable(plugins.get(id)).map(LoadedPlugin::plugin); }
  }
  @Override public void disable(Plugin plugin) {
    if (plugin == null) return;
    String id = plugin.description().id();
    // Dependents first, latest-enabled first: they may still call into this one from onDisable.
    List<Plugin> dependents;
    synchronized (plugins) {
      LoadedPlugin loaded = plugins.get(id);
      if (loaded == null || loaded.plugin != plugin) return;
      dependents = new ArrayList<>();
      for (LoadedPlugin other : plugins.values()) {
        if (other.plugin.description().dependencies().contains(id)) dependents.addFirst(other.plugin);
      }
    }
    for (Plugin dependent : dependents) disable(dependent);
    LoadedPlugin loaded;
    synchronized (plugins) { loaded = plugins.remove(id); }
    if (loaded == null) return;
    if (proxy instanceof ConduitRuntime runtime) runtime.pluginCatalog().remove(id);
    events.fire(new PluginDisableEvent(plugin));
    try { plugin.onDisable(); } catch (Exception | LinkageError exception) { ConduitLog.error("plugin disable failed: " + id, exception); }
    release(plugin);
    close(loaded.resources);
  }
  /** Everything registered under the plugin's name, so none of it outlives the plugin. */
  private void release(Plugin plugin) {
    // Tasks first: a task still running is the likeliest thing to register something new.
    scheduler.retire(plugin);
    events.retire(plugin);
    commands.retire(plugin);
    if (proxy instanceof ConduitRuntime runtime) runtime.pluginReleased(plugin);
  }
  /** Disables every plugin, newest first, so each goes before anything it depends on. */
  public void disableAll() {
    List<Plugin> enabled = new ArrayList<>(plugins());
    for (Plugin plugin : enabled.reversed()) disable(plugin);
  }
  private static void close(AutoCloseable resources) {
    try { resources.close(); } catch (Exception exception) { ConduitLog.warn("could not release plugin resources: " + exception); }
  }
  private record Pending(PluginDescription description, ConduitPlugin plugin, AutoCloseable resources, String format) {}
  private record LoadedPlugin(Plugin plugin, AutoCloseable resources) {}
}
