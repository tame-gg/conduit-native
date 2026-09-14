package gg.tame.conduit.api.plugin;

import java.util.List;

public record PluginDescription(String id, String name, String version, String mainClass, int apiVersion, List<String> dependencies) {
  public PluginDescription {
    id = requireToken(id, "id");
    name = requireText(name, "name");
    version = requireText(version, "version");
    mainClass = requireText(mainClass, "main");
    if (apiVersion < 1) throw new IllegalArgumentException("api-version must be >= 1");
    dependencies = List.copyOf(dependencies == null ? List.of() : dependencies);
  }
  private static String requireToken(String value, String field) {
    String text = requireText(value, field);
    if (!text.matches("[a-z0-9][a-z0-9_-]{0,63}")) throw new IllegalArgumentException("invalid plugin " + field);
    return text;
  }
  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("plugin " + field + " is required");
    if (value.length() > 128) throw new IllegalArgumentException("plugin " + field + " is too long");
    return value.strip();
  }
}
