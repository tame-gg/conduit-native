// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.config;

import gg.tame.conduit.protocol.ProtocolCatalog;
import gg.tame.conduit.protocol.ProtocolVersion;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/** Minimal, strict TOML subset for the foundation configuration. */
public final class ConfigurationLoader {
  private ConfigurationLoader() {}

  public static ConduitConfiguration load(Path path) throws IOException {
    Settings values = new Settings();
    List<String> serverOrder = new ArrayList<>();
    String section = "";
    int lineNumber = 0;
    for (String raw : Files.readAllLines(path)) {
      lineNumber++;
      String line = raw.strip();
      if (line.isEmpty() || line.startsWith("#")) continue;
      if (line.startsWith("[") && line.endsWith("]")) {
        section = line.substring(1, line.length() - 1);
        if (section.startsWith("servers.")) {
          String name = section.substring("servers.".length());
          if (!serverOrder.contains(name)) serverOrder.add(name);
        }
        continue;
      }
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
    List<BackendServer> servers = new ArrayList<>();
    for (String name : serverOrder) {
      String addressKey = "servers." + name + ".address";
      String hostKey = "servers." + name + ".host";
      if (values.containsKey(addressKey) && values.containsKey(hostKey)) throw new IllegalArgumentException("backend " + name + " has both address and host");
      InetSocketAddress address = values.containsKey(addressKey)
          ? parseAddress(values.get(addressKey))
          : new InetSocketAddress(values.get(hostKey), integer(values, "servers." + name + ".port"));
      var loaders = gg.tame.conduit.modded.ModCompatibility.parseList(
          optionalList(values, "servers." + name + ".mod-loaders"));
      servers.add(new BackendServer(name, address, loaders));
    }
    ConduitConfiguration configuration = new ConduitConfiguration(new InetSocketAddress(host, port), maxFrame, mode, secret, servers,
        list(values, "routing.initial"), list(values, "routing.fallback"), authentication(values),
        forwardedAddress(values), ops(values, path.toAbsolutePath().getParent()));
    // A misspelt setting was silently ignored, and its default quietly used in its place.
    for (String key : new java.util.TreeSet<>(values.keySet())) {
      if (!values.read.contains(key)) gg.tame.conduit.log.ConduitLog.warn("Unknown setting " + key + " in " + path.getFileName() + " is ignored");
    }
    return configuration;
  }

  /** The settings, remembering which ones the loader asked for, so those it never did can be named. */
  private static final class Settings extends HashMap<String, String> {
    private final java.util.Set<String> read = new java.util.HashSet<>();
    @Override public String get(Object key) { read.add(String.valueOf(key)); return super.get(key); }
    @Override public boolean containsKey(Object key) { read.add(String.valueOf(key)); return super.containsKey(key); }
    @Override public String getOrDefault(Object key, String fallback) { read.add(String.valueOf(key)); return super.getOrDefault(key, fallback); }
  }

  private static OpsSettings ops(Map<String, String> values, Path configDirectory) {
    int schema = optionalInteger(values, "ops.schema-version", OpsSettings.CURRENT_SCHEMA);
    return new OpsSettings(schema, maintenance(values), health(values), versions(values), shutdown(values), security(values), modded(values), translation(values), status(values, configDirectory),
        metrics(values));
  }

  private static MetricsSettings metrics(Map<String, String> values) {
    String address = optionalString(values, "metrics.prometheus-address", "");
    return new MetricsSettings(address.isBlank() ? Optional.empty() : Optional.of(parseAddress(address)));
  }

  private static StatusSettings status(Map<String, String> values, Path configDirectory) {
    return new StatusSettings(
        StatusSettings.parseMotd(optionalString(values, "status.motd", StatusSettings.DEFAULT_MOTD)),
        optionalInteger(values, "status.display-max-players", StatusSettings.DEFAULT_DISPLAY_MAX_PLAYERS),
        Optional.ofNullable(values.get("status.favicon")).filter(file -> !file.isBlank())
            .flatMap(file -> StatusSettings.favicon(configDirectory.resolve(file).normalize())));
  }

  private static TranslationSettings translation(Map<String, String> values) {
    return new TranslationSettings(
        optionalBoolean(values, "translation.enabled", false),
        TranslationSettings.TranslationEngine.parse(
            optionalString(values, "translation.engine", "via-preferred")),
        optionalBoolean(values, "translation.via-backwards", true),
        optionalBoolean(values, "translation.via-rewind", true),
        optionalBoolean(values, "translation.via-legacy", false),
        optionalString(values, "translation.data-folder", "via"));
  }

  private static ModdedSettings modded(Map<String, String> values) {
    int knownPacks = optionalInteger(values, "protocol.known-packs-limit",
        optionalInteger(values, "modded.known-packs-limit", ModdedSettings.defaults().knownPacksLimit()));
    return new ModdedSettings(
        optionalBoolean(values, "modded.enabled", true),
        knownPacks,
        optionalBoolean(values, "modded.handshake-cache", true),
        optionalInteger(values, "modded.handshake-cache-capacity", 4096),
        optionalInteger(values, "modded.handshake-cache-ttl-ms", 300_000),
        optionalBoolean(values, "modded.forge-compat", true),
        optionalBoolean(values, "modded.neoforge-compat", true),
        optionalBoolean(values, "modded.fabric-compat", true),
        gg.tame.conduit.modded.UnknownModdedPolicy.parse(
            optionalString(values, "modded.unknown-policy", "allow")),
        optionalBoolean(values, "modded.packet-queue-enabled", true),
        optionalInteger(values, "modded.packet-queue-max-depth", 512),
        optionalBoolean(values, "modded.log-mod-handshakes", false));
  }

  private static SecuritySettings security(Map<String, String> values) {
    SecuritySettings.ThrottleSettings throttle = new SecuritySettings.ThrottleSettings(
        optionalBoolean(values, "security.throttle.enabled", true),
        optionalInteger(values, "security.throttle.max-attempts", 40),
        optionalInteger(values, "security.throttle.window-ms", 1_000),
        optionalInteger(values, "security.throttle.max-concurrent", 32),
        optionalInteger(values, "security.throttle.ipv4-prefix", 32),
        optionalInteger(values, "security.throttle.ipv6-prefix", 64),
        optionalInteger(values, "security.throttle.log-interval-ms", 5_000));
    SecuritySettings.BotFilterSettings bot = new SecuritySettings.BotFilterSettings(
        optionalBoolean(values, "security.bot-filter.enabled", true),
        optionalInteger(values, "security.bot-filter.strike-threshold", 10),
        optionalInteger(values, "security.bot-filter.handshake-timeout-ms", 3_000),
        optionalInteger(values, "security.bot-filter.block-duration-ms", 60_000),
        optionalInteger(values, "security.bot-filter.strike-window-ms", 60_000));
    Map<String, SecuritySettings.ChannelAction> channels = new LinkedHashMap<>();
    for (String entry : optionalList(values, "security.channel-guard.block-list")) {
      channels.put(SecuritySettings.ChannelGuardSettings.normalizeChannel(entry), SecuritySettings.ChannelAction.DROP);
    }
    for (String entry : optionalList(values, "security.channel-guard.log-list")) {
      channels.put(SecuritySettings.ChannelGuardSettings.normalizeChannel(entry), SecuritySettings.ChannelAction.LOG);
    }
    for (String entry : optionalList(values, "security.channel-guard.kick-list")) {
      channels.put(SecuritySettings.ChannelGuardSettings.normalizeChannel(entry), SecuritySettings.ChannelAction.KICK);
    }
    if (channels.isEmpty()) channels.putAll(SecuritySettings.ChannelGuardSettings.defaults().channels());
    SecuritySettings.ChannelGuardSettings guard = new SecuritySettings.ChannelGuardSettings(
        optionalBoolean(values, "security.channel-guard.enabled", false),
        SecuritySettings.ChannelAction.parse(optionalString(values, "security.channel-guard.default-action", "log")),
        channels);
    SecuritySettings.AttackModeSettings attack = new SecuritySettings.AttackModeSettings(
        optionalInteger(values, "security.attack-mode.throttle-max-attempts", 8),
        optionalInteger(values, "security.attack-mode.bot-strike-threshold", 3));
    return new SecuritySettings(throttle, bot, guard, attack);
  }

  private static MaintenanceSettings maintenance(Map<String, String> values) {
    return new MaintenanceSettings(
        optionalBoolean(values, "maintenance.enabled", true),
        optionalBoolean(values, "maintenance.active-on-start", false),
        optionalString(values, "maintenance.kick-message", MaintenanceSettings.DEFAULT_KICK),
        optionalString(values, "maintenance.motd", MaintenanceSettings.DEFAULT_MOTD),
        Set.copyOf(optionalList(values, "maintenance.allowlist")));
  }

  private static HealthSettings health(Map<String, String> values) {
    return new HealthSettings(
        optionalBoolean(values, "health.enabled", true),
        optionalInteger(values, "health.interval-ms", 10_000),
        optionalInteger(values, "health.timeout-ms", 1_500),
        optionalInteger(values, "health.failure-threshold", 3),
        optionalInteger(values, "health.success-threshold", 2));
  }

  private static ShutdownSettings shutdown(Map<String, String> values) {
    return new ShutdownSettings(
        optionalBoolean(values, "shutdown.graceful-enabled", true),
        optionalInteger(values, "shutdown.timeout-ms", 5_000),
        optionalString(values, "shutdown.message", ShutdownSettings.DEFAULT_MESSAGE));
  }

  private static VersionGateSettings versions(Map<String, String> values) {
    Set<Integer> allow = new LinkedHashSet<>();
    for (String entry : optionalList(values, "versions.allow")) {
      allow.add(resolveProtocol(entry));
    }
    OptionalInt minimum = OptionalInt.empty();
    OptionalInt maximum = OptionalInt.empty();
    if (values.containsKey("versions.minimum")) minimum = OptionalInt.of(resolveProtocol(values.get("versions.minimum")));
    if (values.containsKey("versions.maximum")) maximum = OptionalInt.of(resolveProtocol(values.get("versions.maximum")));
    return new VersionGateSettings(
        optionalBoolean(values, "versions.enabled", false),
        allow,
        minimum,
        maximum,
        optionalString(values, "versions.ping-version-name", VersionGateSettings.DEFAULT_PING),
        optionalString(values, "versions.kick-message", VersionGateSettings.DEFAULT_KICK),
        optionalString(values, "versions.kick-message-range", VersionGateSettings.DEFAULT_KICK_RANGE),
        optionalBoolean(values, "versions.strict-backend-match", false));
  }

  static int resolveProtocol(String raw) {
    if (raw == null || raw.isBlank()) throw new IllegalArgumentException("empty version token");
    String token = raw.strip();
    if (token.chars().allMatch(Character::isDigit)) return Integer.parseInt(token);
    var release = ProtocolCatalog.findRelease(token);
    if (release.isPresent()) return release.get().protocol();
    String normalized = token.toLowerCase(Locale.ROOT);
    for (ProtocolVersion version : ProtocolVersion.CATALOG) {
      if (version.displayName().equalsIgnoreCase(token)) return version.number();
      if (("minecraft_" + version.displayName().replace('.', '_')).equalsIgnoreCase(normalized)) return version.number();
    }
    throw new IllegalArgumentException("unknown Minecraft version: " + raw);
  }

  private static AuthenticationSettings authentication(Map<String, String> values) {
    AuthenticationMode authMode = values.containsKey("authentication.mode")
        ? AuthenticationMode.parse(required(values, "authentication.mode"))
        : AuthenticationMode.OFFLINE;
    String url = values.getOrDefault("authentication.session-url", AuthenticationSettings.DEFAULT_SESSION_URL);
    int timeout = values.containsKey("authentication.timeout-millis") ? integer(values, "authentication.timeout-millis") : 15_000;
    return new AuthenticationSettings(authMode, url, timeout, optionalBoolean(values, "authentication.kick-existing-players", false));
  }
  private static Optional<java.net.InetAddress> forwardedAddress(Map<String, String> values) {
    String raw = values.get("forwarding.player-address");
    if (raw == null || raw.isBlank()) return Optional.empty();
    try { return Optional.of(java.net.InetAddress.getByName(raw)); }
    catch (java.net.UnknownHostException exception) { throw new IllegalArgumentException("forwarding.player-address is not a valid IP"); }
  }

  private static InetSocketAddress parseAddress(String raw) {
    int colon = raw.lastIndexOf(':');
    if (colon < 1 || colon == raw.length() - 1) throw new IllegalArgumentException("backend address must be host:port");
    String host = raw.substring(0, colon);
    try { return new InetSocketAddress(host, Integer.parseInt(raw.substring(colon + 1))); }
    catch (NumberFormatException exception) { throw new IllegalArgumentException("backend address port must be an integer", exception); }
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
  private static int optionalInteger(Map<String, String> values, String key, int fallback) {
    if (!values.containsKey(key)) return fallback;
    try { return Integer.parseInt(values.get(key).strip()); }
    catch (NumberFormatException exception) { throw new IllegalArgumentException(key + " must be an integer", exception); }
  }
  private static boolean optionalBoolean(Map<String, String> values, String key, boolean fallback) {
    if (!values.containsKey(key)) return fallback;
    String raw = values.get(key).strip().toLowerCase(Locale.ROOT);
    if (raw.equals("true")) return true;
    if (raw.equals("false")) return false;
    throw new IllegalArgumentException(key + " must be true or false");
  }
  private static String optionalString(Map<String, String> values, String key, String fallback) {
    String value = values.get(key);
    return value == null || value.isBlank() ? fallback : value;
  }
  private static List<String> list(Map<String, String> values, String key) {
    String raw = required(values, key);
    return parseList(raw, key);
  }
  private static List<String> optionalList(Map<String, String> values, String key) {
    if (!values.containsKey(key)) return List.of();
    return parseList(values.get(key), key);
  }
  private static List<String> parseList(String raw, String key) {
    String text = raw.strip();
    if (!text.startsWith("[") || !text.endsWith("]")) throw new IllegalArgumentException(key + " must be a string array");
    String contents = text.substring(1, text.length() - 1).strip();
    if (contents.isEmpty()) return List.of();
    List<String> result = new ArrayList<>();
    for (String part : contents.split(",")) {
      String value = part.strip();
      if (value.length() < 2 || !value.startsWith("\"") || !value.endsWith("\"")) {
        throw new IllegalArgumentException(key + " must contain quoted names");
      }
      result.add(value.substring(1, value.length() - 1));
    }
    return result;
  }
}
