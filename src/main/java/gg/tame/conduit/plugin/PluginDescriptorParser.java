package gg.tame.conduit.plugin;

import gg.tame.conduit.api.plugin.PluginDescription;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Line-oriented conduit-plugin.yml parser. Not a general YAML engine. */
public final class PluginDescriptorParser {
  private PluginDescriptorParser() {}
  public static PluginDescription parse(InputStream stream) throws IOException {
    String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    Map<String, String> values = new LinkedHashMap<>();
    List<String> depend = new ArrayList<>();
    for (String raw : text.split("\\R")) {
      String line = raw.strip();
      if (line.isEmpty() || line.startsWith("#")) continue;
      int colon = line.indexOf(':');
      if (colon < 1) throw new IllegalArgumentException("invalid plugin descriptor line: " + line);
      String key = line.substring(0, colon).strip();
      String value = line.substring(colon + 1).strip();
      if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) value = value.substring(1, value.length() - 1);
      if (key.equals("depend") || key.equals("dependencies")) {
        String inner = value;
        if (inner.startsWith("[") && inner.endsWith("]")) inner = inner.substring(1, inner.length() - 1);
        for (String item : inner.split(",")) {
          String id = item.strip();
          if (!id.isEmpty()) depend.add(id);
        }
        continue;
      }
      values.put(key, value);
    }
    String main = first(values, "main", "main-class");
    int api = Integer.parseInt(first(values, "api-version", "apiVersion"));
    return new PluginDescription(first(values, "id"), first(values, "name"), first(values, "version"), main, api, depend);
  }
  private static String first(Map<String, String> values, String... keys) {
    for (String key : keys) {
      String value = values.get(key);
      if (value != null) return value;
    }
    throw new IllegalArgumentException("missing plugin descriptor field");
  }
}
