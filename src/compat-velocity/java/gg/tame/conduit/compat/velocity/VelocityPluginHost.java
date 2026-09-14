package gg.tame.conduit.compat.velocity;

import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.plugin.meta.PluginDependency;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class VelocityPluginHost implements PluginManager {
  private final ConcurrentHashMap<String, Container> plugins = new ConcurrentHashMap<>();
  void add(Container container) { plugins.put(container.getDescription().getId(), container); }
  void disableAll() {
    for (Container container : plugins.values()) container.close();
    plugins.clear();
  }
  void disable(String id) {
    Container container = plugins.remove(id);
    if (container != null) container.close();
  }
  @Override public Optional<PluginContainer> fromInstance(Object instance) {
    for (Container container : plugins.values()) {
      if (container.getInstance().orElse(null) == instance) return Optional.of(container);
    }
    return Optional.empty();
  }
  @Override public Optional<PluginContainer> getPlugin(String id) { return Optional.ofNullable(plugins.get(id)); }
  @Override public Collection<PluginContainer> getPlugins() { return plugins.values().stream().map(c -> (PluginContainer) c).toList(); }
  @Override public boolean isLoaded(String id) { return plugins.containsKey(id); }
  @Override public void addToClasspath(Object plugin, Path path) {
    UnsupportedApis.unsupported("PluginManager.addToClasspath");
  }

  static final class Description implements PluginDescription {
    private final String id;
    private final String name;
    private final String version;
    private final String main;
    private final Path source;
    Description(String id, String name, String version, String main, Path source) {
      this.id = id; this.name = name; this.version = version; this.main = main; this.source = source;
    }
    String main() { return main; }
    @Override public String getId() { return id; }
    @Override public Optional<String> getName() { return Optional.ofNullable(name); }
    @Override public Optional<String> getVersion() { return Optional.ofNullable(version); }
    @Override public Optional<Path> getSource() { return Optional.ofNullable(source); }
    @Override public Collection<PluginDependency> getDependencies() { return java.util.List.of(); }
  }

  static final class Container implements PluginContainer, AutoCloseable {
    private final Description description;
    private final Object instance;
    private final ExecutorService executor;
    Container(Description description, Object instance) {
      this.description = description;
      this.instance = instance;
      this.executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "velocity-plugin-" + description.getId());
        thread.setDaemon(true);
        return thread;
      });
    }
    @Override public PluginDescription getDescription() { return description; }
    @Override public Optional<?> getInstance() { return Optional.ofNullable(instance); }
    @Override public ExecutorService getExecutorService() { return executor; }
    @Override public void close() { executor.shutdownNow(); }
  }
}
