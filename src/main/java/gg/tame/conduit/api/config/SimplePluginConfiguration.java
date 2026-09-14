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
  @Override public Path path() { return path; }
  @Override public Optional<String> string(String key) { return Optional.ofNullable(values.get(key)); }
  @Override public String string(String key, String fallback) { return values.getOrDefault(key, fallback); }
}
