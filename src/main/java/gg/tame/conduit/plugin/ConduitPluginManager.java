package gg.tame.conduit.plugin;

import gg.tame.conduit.Conduit;
import gg.tame.conduit.api.ConduitProxy;
import gg.tame.conduit.api.event.plugin.PluginDisableEvent;
import gg.tame.conduit.api.event.plugin.PluginEnableEvent;
import gg.tame.conduit.api.plugin.ConduitPlugin;
import gg.tame.conduit.api.plugin.Plugin;
import gg.tame.conduit.api.plugin.PluginDescription;
import gg.tame.conduit.api.plugin.PluginManager;
import gg.tame.conduit.command.CommandManager;
import gg.tame.conduit.event.ConduitEventManager;
import gg.tame.conduit.log.ConduitLog;
import gg.tame.conduit.scheduler.ConduitScheduler;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.logging.Logger;

public final class ConduitPluginManager implements PluginManager {
  private final Map<String, LoadedPlugin> plugins = new LinkedHashMap<>();
  private final Path pluginsDirectory;
  private final Path dataRoot;
  private final ConduitProxy proxy;
  private final ConduitEventManager events;
  private final ConduitScheduler scheduler;
  private final CommandManager commands;
  public ConduitPluginManager(Path pluginsDirectory, ConduitProxy proxy, ConduitEventManager events, ConduitScheduler scheduler, CommandManager commands) {
    this.pluginsDirectory = pluginsDirectory;
    this.dataRoot = pluginsDirectory;
    this.proxy = proxy;
    this.events = events;
    this.scheduler = scheduler;
    this.commands = commands;
  }
  public void loadAll() throws IOException {
    Files.createDirectories(pluginsDirectory);
    List<Path> jars = new ArrayList<>();
    try (var stream = Files.list(pluginsDirectory)) {
      stream.filter(path -> path.getFileName().toString().endsWith(".jar")).forEach(jars::add);
    }
    List<Pending> pending = new ArrayList<>();
    for (Path jar : jars) {
      try { pending.add(read(jar)); }
      catch (Exception exception) { ConduitLog.error("rejected plugin jar " + jar.getFileName() + ": " + exception.getMessage()); }
    }
    pending = resolve(pending);
    for (Pending item : pending) enable(item);
  }
  private Pending read(Path jar) throws Exception {
    Path normalized = jar.toAbsolutePath().normalize();
    if (!normalized.startsWith(pluginsDirectory.toAbsolutePath().normalize())) throw new IOException("plugin path escaped plugins directory");
    try (JarFile file = new JarFile(normalized.toFile())) {
      var entry = file.getEntry("conduit-plugin.yml");
      if (entry == null) throw new IOException("missing conduit-plugin.yml");
      PluginDescription description;
      try (var input = file.getInputStream(entry)) { description = PluginDescriptorParser.parse(input); }
      if (description.apiVersion() > Conduit.API_VERSION) {
        throw new IOException("plugin " + description.id() + " requires API " + description.apiVersion() + " (proxy is " + Conduit.API_VERSION + ")");
      }
      URLClassLoader loader = new URLClassLoader(new URL[] { normalized.toUri().toURL() }, ConduitPlugin.class.getClassLoader());
      Class<?> type = Class.forName(description.mainClass(), true, loader);
      if (!ConduitPlugin.class.isAssignableFrom(type)) throw new IOException("main class must extend ConduitPlugin");
      ConduitPlugin plugin = (ConduitPlugin) type.getDeclaredConstructor().newInstance();
      return new Pending(description, plugin, loader);
    }
  }
  private List<Pending> resolve(List<Pending> pending) {
    Map<String, Pending> byId = new LinkedHashMap<>();
    for (Pending item : pending) {
      if (byId.putIfAbsent(item.description.id(), item) != null) {
        ConduitLog.error("duplicate plugin id " + item.description.id());
      }
    }
    List<Pending> ordered = new ArrayList<>();
    List<Pending> remaining = new ArrayList<>(byId.values());
    while (!remaining.isEmpty()) {
      Pending next = null;
      for (Pending item : remaining) {
        if (item.description.dependencies().stream().allMatch(dep -> plugins.containsKey(dep) || ordered.stream().anyMatch(done -> done.description.id().equals(dep)))) {
          next = item;
          break;
        }
      }
      if (next == null) {
        ConduitLog.error("unresolved plugin dependencies: " + remaining.stream().map(item -> item.description.id()).toList());
        break;
      }
      remaining.remove(next);
      ordered.add(next);
    }
    return ordered;
  }
  private void enable(Pending pending) {
    try {
      Path root = pluginsDirectory.toAbsolutePath().normalize();
      Path data = root.resolve(pending.description.id()).normalize();
      if (!data.startsWith(root)) throw new IOException("plugin data path escaped plugins directory");
      Files.createDirectories(data);
      Logger logger = Logger.getLogger("plugin." + pending.description.id());
      pending.plugin.attach(pending.description, proxy, logger, data, scheduler);
      pending.plugin.onLoad();
      pending.plugin.onEnable();
      plugins.put(pending.description.id(), new LoadedPlugin(pending.plugin, pending.loader));
      events.fire(new PluginEnableEvent(pending.plugin));
      ConduitLog.info("Enabled plugin " + pending.description.id() + " " + pending.description.version());
    } catch (Exception exception) {
      ConduitLog.error("failed to enable plugin " + pending.description.id(), exception);
      closeLoader(pending.loader);
    }
  }
  @Override public Collection<Plugin> plugins() { return List.copyOf(plugins.values().stream().map(LoadedPlugin::plugin).toList()); }
  @Override public Optional<Plugin> plugin(String id) { return Optional.ofNullable(plugins.get(id)).map(LoadedPlugin::plugin); }
  @Override public void disable(Plugin plugin) {
    LoadedPlugin loaded = plugins.remove(plugin.description().id());
    if (loaded == null) return;
    try { events.fire(new PluginDisableEvent(plugin)); } catch (RuntimeException ignored) { }
    try { plugin.onDisable(); } catch (RuntimeException exception) { ConduitLog.error("plugin disable failed: " + plugin.description().id(), exception); }
    events.unregister(plugin);
    scheduler.cancel(plugin);
    commands.unregisterAll(plugin);
    closeLoader(loaded.loader);
  }
  public void disableAll() {
    for (Plugin plugin : new ArrayList<>(plugins())) disable(plugin);
  }
  private static void closeLoader(URLClassLoader loader) {
    try { loader.close(); } catch (IOException ignored) { }
  }
  private record Pending(PluginDescription description, ConduitPlugin plugin, URLClassLoader loader) {}
  private record LoadedPlugin(Plugin plugin, URLClassLoader loader) {}
}
