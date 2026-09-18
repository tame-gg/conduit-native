// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.plugin;

import java.util.List;

/**
 * What a plugin says about itself. {@code dependencies} must be enabled first or the plugin is not
 * enabled at all; {@code optionalDependencies} are enabled first when they are present, and are no
 * reason to refuse the plugin when they are not. Dependencies order enabling only: a plugin's
 * classes cannot see another plugin's.
 */
public record PluginDescription(String id, String name, String version, String mainClass, int apiVersion,
                                List<String> dependencies, List<String> optionalDependencies) {
  public PluginDescription(String id, String name, String version, String mainClass, int apiVersion, List<String> dependencies) {
    this(id, name, version, mainClass, apiVersion, dependencies, List.of());
  }
  public PluginDescription {
    id = requireToken(id, "id");
    name = requireText(name, "name");
    version = requireText(version, "version");
    mainClass = requireText(mainClass, "main");
    if (apiVersion < 1) throw new IllegalArgumentException("api-version must be >= 1");
    dependencies = List.copyOf(dependencies == null ? List.of() : dependencies);
    optionalDependencies = List.copyOf(optionalDependencies == null ? List.of() : optionalDependencies);
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
