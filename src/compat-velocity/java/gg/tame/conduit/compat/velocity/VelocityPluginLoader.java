package gg.tame.conduit.compat.velocity;

import com.velocitypowered.api.plugin.Plugin;
import gg.tame.conduit.log.ConduitLog;
import gg.tame.conduit.plugin.ExternalJarHandler;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import javax.inject.Inject;

final class VelocityPluginLoader implements ExternalJarHandler {
  private final VelocityEnvironment environment;
  VelocityPluginLoader(VelocityEnvironment environment) { this.environment = environment; }
  @Override public boolean accepts(JarFile jar) {
    return jar.getEntry("velocity-plugin.json") != null || jar.getEntry("velocity-plugin.toml") != null;
  }
  @Override public void load(Path jar) throws Exception {
    Path normalized = jar.toAbsolutePath().normalize();
    VelocityPluginHost.Description description;
    try (JarFile file = new JarFile(normalized.toFile())) {
      var entry = file.getEntry("velocity-plugin.json");
      if (entry == null) throw new IllegalArgumentException("velocity-plugin.json required");
      try (InputStream input = file.getInputStream(entry)) {
        description = parseJson(new String(input.readAllBytes(), StandardCharsets.UTF_8), normalized);
      }
    }
    URLClassLoader loader = new URLClassLoader(new URL[] { normalized.toUri().toURL() }, VelocityBoot.class.getClassLoader());
    Class<?> type = Class.forName(description.main(), true, loader);
    Plugin annotation = type.getAnnotation(Plugin.class);
    if (annotation != null && (description.getId() == null || description.getId().isBlank())) {
      description = new VelocityPluginHost.Description(annotation.id(), annotation.name(), annotation.version(), description.main(), normalized);
    }
    Object instance = construct(type, description);
    VelocityPluginHost.Container container = new VelocityPluginHost.Container(description, instance);
    environment.plugins().add(container);
    environment.events().register(instance, instance);
    ConduitLog.info("Enabled Velocity plugin " + description.getId() + " " + description.getVersion().orElse(""));
  }
  @Override public void shutdown() { environment.shutdown(); }
  private Object construct(Class<?> type, VelocityPluginHost.Description description) throws Exception {
    Logger logger = Logger.getLogger("velocity-plugin." + description.getId());
    Constructor<?> chosen = null;
    for (Constructor<?> constructor : type.getDeclaredConstructors()) {
      if (chosen == null || constructor.getParameterCount() > chosen.getParameterCount()) chosen = constructor;
    }
    if (chosen == null) throw new IllegalStateException("no constructor");
    chosen.setAccessible(true);
    Object[] args = new Object[chosen.getParameterCount()];
    Class<?>[] types = chosen.getParameterTypes();
    VelocityPluginHost.Container pending = new VelocityPluginHost.Container(description, null);
    for (int i = 0; i < types.length; i++) args[i] = inject(types[i], logger, pending);
    Object instance = chosen.newInstance(args);
    for (Field field : type.getDeclaredFields()) {
      if (field.getAnnotation(Inject.class) == null) continue;
      field.setAccessible(true);
      field.set(instance, inject(field.getType(), logger, pending));
    }
    return instance;
  }
  private Object inject(Class<?> type, Logger logger, VelocityPluginHost.Container container) {
    if (type.isAssignableFrom(environment.proxy().getClass())) return environment.proxy();
    if (type == com.velocitypowered.api.proxy.ProxyServer.class) return environment.proxy();
    if (type == Logger.class) return logger;
    if (type == org.slf4j.Logger.class) return org.slf4j.LoggerFactory.getLogger(logger.getName());
    if (type == com.velocitypowered.api.plugin.PluginContainer.class) return container;
    if (type.isAssignableFrom(VelocityPluginHost.Container.class)) return container;
    if (Path.class.isAssignableFrom(type)) {
      try {
        Path data = Path.of("plugins", container.getDescription().getId());
        java.nio.file.Files.createDirectories(data);
        return data;
      } catch (java.io.IOException exception) {
        throw new IllegalStateException(exception);
      }
    }
    throw new IllegalArgumentException("cannot inject " + type.getName());
  }
  private static VelocityPluginHost.Description parseJson(String json, Path source) {
    return new VelocityPluginHost.Description(field(json, "id"), field(json, "name"), field(json, "version"), field(json, "main"), source);
  }
  private static String field(String json, String key) {
    String needle = "\"" + key + "\"";
    int at = json.indexOf(needle);
    if (at < 0) return "";
    int colon = json.indexOf(':', at);
    int quote = json.indexOf('"', colon + 1);
    int end = json.indexOf('"', quote + 1);
    if (quote < 0 || end < 0) return "";
    return json.substring(quote + 1, end);
  }
}
