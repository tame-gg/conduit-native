// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.compat.velocity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.plugin.meta.PluginDependency;
import gg.tame.conduit.api.plugin.PluginDescription;
import gg.tame.conduit.api.plugin.PluginLoader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * Reads Velocity plugin jars: {@code velocity-plugin.json} at the jar root, which is what the
 * velocity-api annotation processor writes from a plugin's {@code @Plugin}. Conduit's plugin
 * manager then orders, enables and disables what this returns.
 */
final class VelocityPluginLoader implements PluginLoader {
  static final String METADATA = "velocity-plugin.json";
  private final VelocityEnvironment environment;
  VelocityPluginLoader(VelocityEnvironment environment) { this.environment = environment; }

  @Override public String format() { return "velocity"; }
  @Override public boolean accepts(JarFile jar) { return jar.getEntry(METADATA) != null; }

  @Override public Loaded load(Path jar) throws Exception {
    VelocityPluginHost.Description description;
    try (JarFile file = new JarFile(jar.toFile())) {
      ZipEntry entry = file.getEntry(METADATA);
      if (entry == null) throw new IOException("missing " + METADATA);
      try (InputStream input = file.getInputStream(entry)) {
        description = parse(new String(input.readAllBytes(), StandardCharsets.UTF_8), jar);
      }
    }
    VelocityClassLoader loader = new VelocityClassLoader(jar.toUri().toURL(), environment.loaders);
    try {
      // Found now so a jar whose main class is missing is rejected with the rest; initialized in onLoad.
      Class<?> main = Class.forName(description.main(), false, loader);
      List<String> required = new ArrayList<>();
      List<String> optional = new ArrayList<>();
      for (PluginDependency dependency : description.dependencies()) (dependency.isOptional() ? optional : required).add(dependency.getId());
      PluginDescription nativeDescription = new PluginDescription(description.id(),
          description.name() == null ? description.id() : description.name(),
          description.version() == null ? "unknown" : description.version(), description.main(), 1, required, optional);
      return new Loaded(nativeDescription, new VelocityPluginHandle(environment, description, loader, main), loader);
    } catch (Exception | LinkageError failed) {
      loader.close();
      throw failed;
    }
  }

  static VelocityPluginHost.Description parse(String json, Path source) {
    JsonObject root;
    try {
      root = JsonParser.parseString(json).getAsJsonObject();
    } catch (RuntimeException malformed) {
      throw new IllegalArgumentException(METADATA + " is not a JSON object: " + malformed.getMessage(), malformed);
    }
    String id = text(root, "id");
    if (id == null || !com.velocitypowered.api.plugin.PluginDescription.ID_PATTERN.matcher(id).matches()) {
      throw new IllegalArgumentException(METADATA + " has no valid plugin id (lowercase, starts with a letter, at most 64 characters): " + id);
    }
    String main = text(root, "main");
    if (main == null || main.isBlank()) throw new IllegalArgumentException(METADATA + " of " + id + " names no main class");
    List<String> authors = new ArrayList<>();
    for (JsonElement author : array(root, "authors")) authors.add(author.getAsString());
    List<PluginDependency> dependencies = new ArrayList<>();
    for (JsonElement element : array(root, "dependencies")) {
      if (!element.isJsonObject()) throw new IllegalArgumentException(METADATA + " of " + id + " has a malformed dependency");
      JsonObject dependency = element.getAsJsonObject();
      String dependsOn = text(dependency, "id");
      if (dependsOn == null || dependsOn.isBlank()) throw new IllegalArgumentException(METADATA + " of " + id + " has a dependency without an id");
      boolean optional = dependency.has("optional") && dependency.get("optional").getAsBoolean();
      dependencies.add(new PluginDependency(dependsOn, text(dependency, "version"), optional));
    }
    return new VelocityPluginHost.Description(id, text(root, "name"), text(root, "version"), text(root, "description"),
        text(root, "url"), List.copyOf(authors), List.copyOf(dependencies), source, main);
  }
  private static String text(JsonObject object, String key) {
    JsonElement value = object.get(key);
    if (value == null || value.isJsonNull()) return null;
    if (!value.isJsonPrimitive()) throw new IllegalArgumentException(METADATA + " field " + key + " must be a string");
    String text = value.getAsString();
    return text.isBlank() ? null : text;
  }
  private static JsonArray array(JsonObject object, String key) {
    JsonElement value = object.get(key);
    if (value == null || value.isJsonNull()) return new JsonArray();
    if (!value.isJsonArray()) throw new IllegalArgumentException(METADATA + " field " + key + " must be an array");
    return value.getAsJsonArray();
  }
}
