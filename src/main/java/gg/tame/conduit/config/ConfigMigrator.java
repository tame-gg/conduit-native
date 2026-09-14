package gg.tame.conduit.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Appends missing documented Ops defaults without rewriting existing operator values.
 * Comment preservation is best-effort for the flat loader (PARTIAL).
 */
public final class ConfigMigrator {
  public static final int TARGET_SCHEMA = OpsSettings.CURRENT_SCHEMA;

  private ConfigMigrator() {}

  public record Result(boolean changed, List<String> addedKeys) {
    public Result {
      addedKeys = List.copyOf(addedKeys);
    }
  }

  public static Result migrate(Path path) throws IOException {
    if (!Files.isRegularFile(path)) throw new IllegalArgumentException("config file missing: " + path);
    List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
    Set<String> present = indexKeys(lines);
    List<String> added = new ArrayList<>();
    StringBuilder appendix = new StringBuilder();

    appendMissingSection(appendix, present, added, "maintenance", List.of(
        entry("maintenance.enabled", "enabled = true"),
        entry("maintenance.active-on-start", "active-on-start = false"),
        entry("maintenance.kick-message", "kick-message = \"" + MaintenanceSettings.DEFAULT_KICK + "\""),
        entry("maintenance.motd", "motd = \"" + MaintenanceSettings.DEFAULT_MOTD + "\""),
        entry("maintenance.allowlist", "allowlist = []")));
    appendMissingSection(appendix, present, added, "health", List.of(
        entry("health.enabled", "enabled = true"),
        entry("health.interval-ms", "interval-ms = 10000"),
        entry("health.timeout-ms", "timeout-ms = 1500"),
        entry("health.failure-threshold", "failure-threshold = 3"),
        entry("health.success-threshold", "success-threshold = 2")));
    appendMissingSection(appendix, present, added, "versions", List.of(
        entry("versions.enabled", "enabled = false"),
        entry("versions.allow", "allow = []"),
        entry("versions.ping-version-name", "ping-version-name = \"" + VersionGateSettings.DEFAULT_PING + "\""),
        entry("versions.kick-message", "kick-message = \"" + VersionGateSettings.DEFAULT_KICK + "\""),
        entry("versions.kick-message-range", "kick-message-range = \"" + VersionGateSettings.DEFAULT_KICK_RANGE + "\"")));
    appendMissingSection(appendix, present, added, "shutdown", List.of(
        entry("shutdown.graceful-enabled", "graceful-enabled = true"),
        entry("shutdown.timeout-ms", "timeout-ms = 5000"),
        entry("shutdown.message", "message = \"" + ShutdownSettings.DEFAULT_MESSAGE + "\"")));
    if (!present.contains("ops.schema-version")) {
      if (!appendix.isEmpty()) appendix.append('\n');
      appendix.append("# Conduit Ops schema\n");
      appendix.append("[ops]\n");
      appendix.append("schema-version = ").append(TARGET_SCHEMA).append('\n');
      added.add("ops.schema-version");
      present.add("ops.schema-version");
    }
    if (added.isEmpty()) return new Result(false, List.of());
    Files.writeString(path, (lines.isEmpty() || lines.get(lines.size() - 1).isBlank() ? "" : "\n") + appendix,
        StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    return new Result(true, added);
  }

  private static void appendMissingSection(StringBuilder appendix, Set<String> present, List<String> added,
                                           String section, List<Map.Entry<String, String>> entries) {
    List<Map.Entry<String, String>> missing = new ArrayList<>();
    for (Map.Entry<String, String> entry : entries) {
      if (!present.contains(entry.getKey())) missing.add(entry);
    }
    if (missing.isEmpty()) return;
    if (!appendix.isEmpty()) appendix.append('\n');
    appendix.append("# Conduit Ops defaults (schema ").append(TARGET_SCHEMA).append(")\n");
    appendix.append('[').append(section).append("]\n");
    for (Map.Entry<String, String> entry : missing) {
      appendix.append(entry.getValue()).append('\n');
      added.add(entry.getKey());
      present.add(entry.getKey());
    }
  }

  private static Map.Entry<String, String> entry(String key, String line) {
    return Map.entry(key, line);
  }

  private static Set<String> indexKeys(List<String> lines) {
    Set<String> values = new LinkedHashSet<>();
    String section = "";
    for (String raw : lines) {
      String line = raw.strip();
      if (line.isEmpty() || line.startsWith("#")) continue;
      if (line.startsWith("[") && line.endsWith("]")) {
        section = line.substring(1, line.length() - 1);
        continue;
      }
      int equals = line.indexOf('=');
      if (equals < 1 || section.isEmpty()) continue;
      values.add(section + "." + line.substring(0, equals).strip());
    }
    return values;
  }
}
