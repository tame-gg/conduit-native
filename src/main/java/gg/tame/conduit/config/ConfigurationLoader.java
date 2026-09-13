package gg.tame.conduit.config;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Minimal, strict TOML subset for the foundation configuration. */
public final class ConfigurationLoader {
  private ConfigurationLoader() {}

  public static ConduitConfiguration load(Path path) throws IOException {
    Map<String, String> values = new HashMap<>();
    String section = "";
    int lineNumber = 0;
    for (String raw : Files.readAllLines(path)) {
      lineNumber++;
      String line = raw.strip();
      if (line.isEmpty() || line.startsWith("#")) continue;
      if (line.startsWith("[") && line.endsWith("]")) { section = line.substring(1, line.length() - 1); continue; }
      int equals = line.indexOf('=');
      if (equals < 1 || section.isEmpty()) throw new IllegalArgumentException("invalid configuration at line " + lineNumber);
      String key = section + "." + line.substring(0, equals).strip();
      String value = line.substring(equals + 1).strip();
      if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) value = value.substring(1, value.length() - 1);
      if (values.putIfAbsent(key, value) != null) throw new IllegalArgumentException("duplicate setting: " + key);
    }
    String host = required(values, "listener.host");
    int port = integer(values, "listener.port");
    int maxFrame = integer(values, "listener.max-frame-bytes");
    ForwardingMode mode = ForwardingMode.parse(required(values, "forwarding.mode"));
    Optional<Path> secret = Optional.ofNullable(values.get("forwarding.secret-file")).map(value -> path.getParent().resolve(value).normalize());
    return new ConduitConfiguration(new InetSocketAddress(host, port), maxFrame, mode, secret);
  }

  private static String required(Map<String, String> values, String key) {
    String value = values.get(key);
    if (value == null || value.isBlank()) throw new IllegalArgumentException("missing required setting: " + key);
    return value;
  }
  private static int integer(Map<String, String> values, String key) {
    try { return Integer.parseInt(required(values, key)); }
    catch (NumberFormatException exception) { throw new IllegalArgumentException(key + " must be an integer", exception); }
  }
}
