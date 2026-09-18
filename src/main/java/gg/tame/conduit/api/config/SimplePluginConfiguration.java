package gg.tame.conduit.api.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class SimplePluginConfiguration implements PluginConfiguration {
  private final Path path;
  private final Map<String, String> values = new LinkedHashMap<>();
  public SimplePluginConfiguration(Path path) throws IOException {
    this.path = path;
    if (!Files.exists(path)) return;
    for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
      String line = raw.strip();
      if (line.isEmpty() || line.startsWith("#")) continue;
      int equals = line.indexOf('=');
      if (equals < 1) continue;
      values.put(line.substring(0, equals).strip(), line.substring(equals + 1).strip());
    }
  }
  /**
   * Reads {@code path}, creating it from {@code defaults} when it is not there yet. Keys the file
   * is missing are added back to it, so a plugin that gains a setting does not need a migration.
   */
  public static SimplePluginConfiguration load(Path path, Map<String, String> defaults) throws IOException {
    SimplePluginConfiguration configuration = new SimplePluginConfiguration(path);
    StringBuilder added = new StringBuilder();
    for (Map.Entry<String, String> entry : defaults.entrySet()) {
      if (configuration.values.containsKey(entry.getKey())) continue;
      configuration.values.put(entry.getKey(), entry.getValue());
      added.append(entry.getKey()).append('=').append(entry.getValue()).append(System.lineSeparator());
    }
    if (added.isEmpty()) return configuration;
    if (path.getParent() != null) Files.createDirectories(path.getParent());
    if (Files.exists(path) && Files.size(path) > 0
        && !Files.readString(path, StandardCharsets.UTF_8).endsWith("\n")) {
      added.insert(0, System.lineSeparator());
    }
    Files.writeString(path, added, StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    return configuration;
  }
  @Override public Path path() { return path; }
  @Override public Optional<String> string(String key) { return Optional.ofNullable(values.get(key)); }
  @Override public String string(String key, String fallback) { return values.getOrDefault(key, fallback); }
}
