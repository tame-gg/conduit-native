// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.config;

import gg.tame.conduit.log.ConduitLog;
import gg.tame.conduit.protocol.ProtocolCatalog;
import gg.tame.conduit.protocol.ProtocolVersion;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
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
  /** Where a modern-forwarding secret lives when the configuration does not say. */
  private static final String DEFAULT_SECRET_FILE = "forwarding.secret";

  private ConfigurationLoader() {}

  /**
   * A mistake in the file is an IllegalArgumentException whose message is the whole story for the
   * operator: the file, the line and what is written there when it comes from one setting, and what
   * is allowed. The launcher prints it alone, without a stack trace.
   */
  /** Reads and validates a configuration, writing nothing. */
  public static ConduitConfiguration load(Path path) throws IOException {
    return load(path, false);
  }

  /**
   * Reads and validates a configuration.
   *
   * @param createSecret when true, a modern-forwarding secret file that does not
   *     exist yet is created rather than refused. Only a real start passes true:
   *     validating a configuration must not write to the disk it is validating.
   */
  public static ConduitConfiguration load(Path path, boolean createSecret) throws IOException {
    String file = String.valueOf(path.getFileName());
    List<String> lines;
    try { lines = Files.readAllLines(path); }
    catch (NoSuchFileException missing) {
      throw new IllegalArgumentException(path.toAbsolutePath() + " does not exist. Copy the sample conduit.toml there and edit it.");
    } catch (CharacterCodingException notUtf8) {
      throw new IllegalArgumentException(file + " is not UTF-8 text. Save it as UTF-8.");
    }
    Settings values = new Settings();
    List<String> serverOrder = new ArrayList<>();
    String section = "";
    int lineNumber = 0;
    for (String raw : lines) {
      lineNumber++;
      String line = withoutComment(raw).strip();
      if (line.isEmpty()) continue;
      if (line.startsWith("[")) {
        if (!line.endsWith("]")) throw malformed(file, lineNumber, raw, "a [section] header must end with ]");
        section = line.substring(1, line.length() - 1);
        if (section.startsWith("servers.")) {
          String name = section.substring("servers.".length());
          if (!serverOrder.contains(name)) serverOrder.add(name);
          values.origins.putIfAbsent(section, new Origin(lineNumber, ""));
        }
        continue;
      }
      int equals = line.indexOf('=');
      if (equals < 1) throw malformed(file, lineNumber, raw, "expected key = value");
      if (section.isEmpty()) throw malformed(file, lineNumber, raw, "a setting must come after a [section] header");
      String key = section + "." + line.substring(0, equals).strip();
      String written = line.substring(equals + 1).strip();
      String value = written;
      if (value.startsWith("\"")) {
        // Left open, the quote became part of the value: a host that never resolves, a MOTD with a stray ".
        if (value.length() < 2 || !value.endsWith("\"")) throw malformed(file, lineNumber, raw, "a string must end with \"");
        value = value.substring(1, value.length() - 1);
      }
      if (written.startsWith("[") && !written.endsWith("]")) throw malformed(file, lineNumber, raw, "an array must open and close on one line");
      Origin first = values.origins.putIfAbsent(key, new Origin(lineNumber, written));
      if (first != null) throw malformed(file, lineNumber, raw, key + " is already set on line " + first.line());
      values.put(key, value);
    }
    try {
      ConduitConfiguration configuration = build(path, values, serverOrder, createSecret);
      // A misspelt setting was silently ignored, and its default quietly used in its place.
      for (String key : new java.util.TreeSet<>(values.keySet())) {
        if (!values.read.contains(key)) ConduitLog.warn("Unknown setting " + key + " in " + file + " is ignored");
      }
      VersionGateSettings gate = configuration.versions();
      // The other way round, and the more expensive mistake: rules written but switched off. Every
      // version still joins, and the server list advertises the whole range rather than the one the
      // operator meant to name, with nothing anywhere to say why.
      if (!gate.enabled() && gate.hasConstraints()) {
        ConduitLog.warn("versions.allow / versions.minimum / versions.maximum are set in " + file
            + ", but versions.enabled is false, so every Minecraft version may still join and the server"
            + " list advertises every version Conduit carries. Set versions.enabled = true to apply them.");
      }
      if (gate.enabled() && !gate.hasConstraints()) {
        ConduitLog.warn("versions.enabled is true in " + file + ", but versions.allow, versions.minimum and versions.maximum"
            + " are all unset, so no Minecraft version is turned away");
      }
      return configuration;
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(values.describe(file, String.valueOf(invalid.getMessage())), invalid);
    }
  }

  private static ConduitConfiguration build(Path path, Settings values, List<String> serverOrder,
      boolean createSecret) throws IOException {
    String file = String.valueOf(path.getFileName());
    InetSocketAddress listener = new InetSocketAddress(required(values, "listener.host"), port(values, "listener.port"));
    // Unresolved, it failed only at bind, as an UnresolvedAddressException with no message at all.
    if (listener.isUnresolved()) throw new IllegalArgumentException("listener.host must be an IP address or a host name that resolves, such as 0.0.0.0 or 127.0.0.1");
    int maxFrame = integer(values, "listener.max-frame-bytes");
    ForwardingMode mode = ForwardingMode.parse(required(values, "forwarding.mode"));
    Path configDirectory = path.toAbsolutePath().getParent();
    // Modern forwarding needs a secret and there is nothing useful to choose, so
    // an unset secret-file is a default rather than a mistake: the file sits
    // beside the configuration and is created on first start. It is created for
    // every mode, not only "modern", so that turning modern forwarding on later
    // is one line in this file and a copy into each backend -- an operator who
    // had to generate the secret first tended to invent a weak one by hand.
    Optional<Path> secret = Optional.ofNullable(values.get("forwarding.secret-file"))
        .or(() -> Optional.of(DEFAULT_SECRET_FILE))
        .map(value -> configDirectory.resolve(value).normalize());
    List<BackendServer> servers = new ArrayList<>();
    Map<String, String> lowerCaseNames = new HashMap<>();
    for (String name : serverOrder) {
      String prefix = "servers." + name;
      if (!name.matches("[a-zA-Z0-9_-]{1,64}")) throw new IllegalArgumentException(prefix + " is not a valid server name: use 1 to 64 letters, digits, _ or -");
      // Lookups ignore case, so the second of these was refused only when the registry was built.
      String earlier = lowerCaseNames.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
      if (earlier != null) throw new IllegalArgumentException(prefix + " has the same name as servers." + earlier + " (server names ignore case)");
      String addressKey = prefix + ".address";
      String hostKey = prefix + ".host";
      boolean hasAddress = values.containsKey(addressKey);
      if (hasAddress && values.containsKey(hostKey)) throw new IllegalArgumentException(prefix + " has both address and host; use one");
      if (!hasAddress && !values.containsKey(hostKey)) throw new IllegalArgumentException(prefix + " needs host and port, or address = \"host:port\"");
      InetSocketAddress address = hasAddress
          ? parseAddress(values, addressKey)
          : new InetSocketAddress(required(values, hostKey), port(values, prefix + ".port"));
      if (address.isUnresolved()) {
        // Legal (its DNS may not be up yet), but the lookup is never retried: every connect fails until a restart.
        ConduitLog.warn(prefix + " host " + address.getHostString() + " in " + file + " does not resolve; Conduit looks it up"
            + " only at start, so " + name + " is unreachable until a restart");
      } else if (address.getPort() == listener.getPort() && (address.getAddress().equals(listener.getAddress())
          || listener.getAddress().isAnyLocalAddress() && address.getAddress().isLoopbackAddress())) {
        throw new IllegalArgumentException(prefix + " is Conduit's own listener (" + address.getHostString() + ":" + address.getPort()
            + "); a server needs another address or port");
      }
      String loadersKey = prefix + ".mod-loaders";
      java.util.Set<gg.tame.conduit.modded.ModLoaderFamily> loaders;
      try { loaders = gg.tame.conduit.modded.ModCompatibility.parseList(optionalList(values, loadersKey)); }
      catch (IllegalArgumentException unknown) {
        throw new IllegalArgumentException(loadersKey + " may only name vanilla, fabric, quilt, forge or neoforge (" + unknown.getMessage() + ")");
      }
      servers.add(new BackendServer(name, address, loaders));
    }
    ConduitConfiguration configuration = new ConduitConfiguration(listener, maxFrame, mode, secret, servers,
        list(values, "routing.initial"), list(values, "routing.fallback"), authentication(values),
        forwardedAddress(values), ops(values, path.toAbsolutePath().getParent()),
        optionalBoolean(values, "listener.proxy-protocol", false), forcedHosts(values));
    if (configuration.forwardingSecretFile().isPresent()) {
      // Otherwise first read by the launcher, where a missing file was a bare NoSuchFileException stack trace.
      Path secretFile = configuration.forwardingSecretFile().get();
      if (createSecret && gg.tame.conduit.forwarding.ForwardingSecret.createIfAbsent(secretFile)) {
        if (mode == ForwardingMode.MODERN) {
          ConduitLog.warn("Created a modern forwarding secret at " + secretFile
              + ". Every backend must be given the same value -- for Paper, velocity.secret in"
              + " config/paper-global.yml -- and must run with online-mode=false, since Conduit"
              + " authenticates instead. Until then those backends will refuse this proxy's logins.");
        } else {
          // Written ahead of being wanted, so forwarding.mode = "modern" is the
          // only change needed later. Not a warning: nothing is wrong yet.
          ConduitLog.info("Created a forwarding secret at " + secretFile + ". forwarding.mode is \""
              + mode.name().toLowerCase(Locale.ROOT) + "\", so nothing uses it yet; set it to \"modern\" and copy this"
              + " value into every backend when you want signed forwarding.");
        }
      }
      // Only modern forwarding reads it, so an unreadable or empty file is a
      // mistake only then. In any other mode the file is a convenience, and
      // refusing to start over one Conduit itself had just created would be absurd.
      if (mode == ForwardingMode.MODERN) {
        String text;
        try { text = Files.readString(secretFile); }
        catch (NoSuchFileException missing) {
          // No trailing sentence: the loader appends `, found "<value>"` when the
          // setting came from a line, and a full stop before that reads as a typo.
          throw new IllegalArgumentException("forwarding.secret-file does not exist at " + secretFile
              + " (starting Conduit creates it; --check-config does not write files)");
        }
        catch (IOException unreadable) { throw new IllegalArgumentException("forwarding.secret-file cannot be read at " + secretFile + " (" + unreadable.getClass().getSimpleName() + ")"); }
        if (text.isBlank()) throw new IllegalArgumentException("forwarding.secret-file is empty at " + secretFile);
      }
    }
    configuration.ops().metrics().prometheusAddress().ifPresent(metrics -> {
      // Otherwise the bind failed at start, was logged once, and the proxy ran without metrics.
      boolean wildcard = metrics.getAddress().isAnyLocalAddress() || listener.getAddress().isAnyLocalAddress();
      if (metrics.getPort() == listener.getPort() && (wildcard || metrics.getAddress().equals(listener.getAddress()))) {
        throw new IllegalArgumentException("metrics.prometheus-address uses the port Conduit listens on (" + listener.getHostString()
            + ":" + listener.getPort() + "); give it another port");
      }
    });
    return configuration;
  }

  private record Origin(int line, String written) {}

  /** The settings, remembering which ones the loader asked for, so those it never did can be named. */
  private static final class Settings extends HashMap<String, String> {
    private final java.util.Set<String> read = new java.util.HashSet<>();
    /** Where each setting, and each [servers.<name>] header, was written, so a message can point there. */
    private final Map<String, Origin> origins = new HashMap<>();
    @Override public String get(Object key) { read.add(String.valueOf(key)); return super.get(key); }
    @Override public boolean containsKey(Object key) { read.add(String.valueOf(key)); return super.containsKey(key); }
    @Override public String getOrDefault(Object key, String fallback) { read.add(String.valueOf(key)); return super.getOrDefault(key, fallback); }

    /**
     * Every message about a setting starts with its key, here and in the settings records, so the
     * file's line and value are added in this one place.
     */
    String describe(String file, String message) {
      Origin origin = origins.get(message.split("[ :]", 2)[0]);
      if (origin == null) return file + ": " + message;
      return file + " line " + origin.line() + ": " + message + (origin.written().isEmpty() ? "" : ", found " + origin.written());
    }
  }

  private static IllegalArgumentException malformed(String file, int line, String text, String problem) {
    return new IllegalArgumentException(file + " line " + line + ": " + problem + ", found " + text.strip());
  }

  /**
   * The line up to a # comment outside a quoted string. A trailing comment used to become part of the
   * value: port = 25565 # default was "not an integer", and a commented host never resolved.
   */
  private static String withoutComment(String line) {
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '"') quoted = !quoted;
      else if (c == '\\' && quoted) i++;
      else if (c == '#' && !quoted) return line.substring(0, i);
    }
    return line;
  }

  private static OpsSettings ops(Map<String, String> values, Path configDirectory) {
    int schema = optionalInteger(values, "ops.schema-version", OpsSettings.CURRENT_SCHEMA);
    return new OpsSettings(schema, maintenance(values), health(values), versions(values), shutdown(values), security(values), modded(values), translation(values), status(values, configDirectory),
        metrics(values), updates(values), messaging(values), routingSettings(values), bans(values));
  }

  private static MetricsSettings metrics(Map<String, String> values) {
    String key = "metrics.prometheus-address";
    if (optionalString(values, key, "").isBlank()) return MetricsSettings.defaults();
    InetSocketAddress address = parseAddress(values, key);
    if (address.isUnresolved()) throw new IllegalArgumentException(key + " must be an IP address or a host name that resolves, with a port");
    return new MetricsSettings(Optional.of(address));
  }

  private static BanSettings bans(Map<String, String> values) {
    return new BanSettings(
        optionalString(values, "bans.message", BanSettings.DEFAULT_MESSAGE),
        optionalString(values, "bans.temporary", BanSettings.DEFAULT_TEMPORARY),
        // Blank is allowed here: a network may not want a permanent ban to say so.
        values.containsKey("bans.permanent") ? values.get("bans.permanent") : BanSettings.DEFAULT_PERMANENT,
        optionalString(values, "bans.default-reason", BanSettings.DEFAULT_REASON));
  }

  private static RoutingSettings routingSettings(Map<String, String> values) {
    return new RoutingSettings(optionalBoolean(values, "routing.fallback-on-kick",
        RoutingSettings.DEFAULT_FALLBACK_ON_KICK));
  }

  private static MessagingSettings messaging(Map<String, String> values) {
    return new MessagingSettings(optionalBoolean(values, "messaging.bungeecord-channel",
        MessagingSettings.DEFAULT_BUNGEECORD_CHANNEL));
  }

  private static UpdateSettings updates(Map<String, String> values) {
    return new UpdateSettings(
        optionalBoolean(values, "updates.via", UpdateSettings.DEFAULT_VIA),
        optionalBoolean(values, "updates.check-only", false),
        optionalInteger(values, "updates.timeout-ms", UpdateSettings.DEFAULT_TIMEOUT_MS),
        optionalInteger(values, "updates.via-check-interval-hours", UpdateSettings.DEFAULT_CHECK_INTERVAL_HOURS));
  }

  /**
   * {@code [forced-hosts]}: each key a hostname the client writes in its handshake, each value the
   * server to send it to, or a list of servers to try in order. A quoted key keeps its quotes here,
   * as the reader only unquotes values, so they come off before the host is used.
   */
  private static gg.tame.conduit.routing.ForcedHosts forcedHosts(Map<String, String> values) {
    String prefix = "forced-hosts.";
    Map<String, List<String>> hosts = new LinkedHashMap<>();
    for (String key : new java.util.TreeSet<>(values.keySet())) {
      if (!key.startsWith(prefix)) continue;
      String host = unquote(key.substring(prefix.length()));
      String written = values.get(key);
      List<String> servers = written != null && written.strip().startsWith("[")
          ? parseList(written, key)
          : List.of(written == null ? "" : written);
      if (servers.size() == 1 && servers.getFirst().isBlank()) {
        throw new IllegalArgumentException(key + " must name a server, or a list of them");
      }
      hosts.put(host, servers);
    }
    return gg.tame.conduit.routing.ForcedHosts.of(hosts);
  }

  private static String unquote(String text) {
    if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) return text.substring(1, text.length() - 1);
    return text;
  }

  private static StatusSettings status(Map<String, String> values, Path configDirectory) {
    return new StatusSettings(
        StatusSettings.parseMotd(optionalString(values, "status.motd", StatusSettings.DEFAULT_MOTD)),
        optionalInteger(values, "status.display-max-players", StatusSettings.DEFAULT_DISPLAY_MAX_PLAYERS),
        Optional.ofNullable(values.get("status.favicon")).filter(file -> !file.isBlank())
            .flatMap(file -> StatusSettings.favicon(configDirectory.resolve(file).normalize())),
        StatusSettings.FaviconPolicy.parse(optionalString(values, "status.favicon-policy", "plugins")),
        optionalInteger(values, "status.player-sample", StatusSettings.DEFAULT_PLAYER_SAMPLE),
        optionalBoolean(values, "status.player-sample-server", false));
  }

  private static TranslationSettings translation(Map<String, String> values) {
    return new TranslationSettings(
        optionalBoolean(values, "translation.enabled", true),
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
      allow.add(protocol("versions.allow", entry));
    }
    OptionalInt minimum = OptionalInt.empty();
    OptionalInt maximum = OptionalInt.empty();
    if (values.containsKey("versions.minimum")) minimum = OptionalInt.of(protocol("versions.minimum", values.get("versions.minimum")));
    if (values.containsKey("versions.maximum")) maximum = OptionalInt.of(protocol("versions.maximum", values.get("versions.maximum")));
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
    // A release Via carries but Conduit has no catalog entry for -- the newest one, usually, since
    // Via ships it first. Refusing to start over a version the proxy can actually carry made an
    // operator edit out the version their players are on.
    var carried = gg.tame.conduit.viaversion.ConduitViaSupport.protocolByName(token);
    if (carried.isPresent()) return carried.getAsInt();
    throw new IllegalArgumentException("unknown Minecraft version: " + raw);
  }
  private static int protocol(String key, String raw) {
    try { return resolveProtocol(raw); }
    catch (IllegalArgumentException unknown) {
      // A release neither table has, which is usually one newer than both. The wording is the one
      // ConfigValidationTests pins, and it already names the way through: a protocol number is
      // taken as written, so a version only a newer ViaVersion carries can still be gated on.
      throw new IllegalArgumentException(key + " must name Minecraft releases such as 1.20.4, or protocol numbers (" + unknown.getMessage() + ")");
    }
  }

  private static AuthenticationSettings authentication(Map<String, String> values) {
    // Online when the file does not say. Offline is the setting that lets anyone
    // join as anyone, and it should not be what a missing line means.
    AuthenticationMode authMode = values.containsKey("authentication.mode")
        ? AuthenticationMode.parse(required(values, "authentication.mode"))
        : AuthenticationMode.ONLINE;
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

  private static InetSocketAddress parseAddress(Map<String, String> values, String key) {
    String raw = values.get(key).strip();
    int colon = raw.lastIndexOf(':');
    if (colon < 1 || colon == raw.length() - 1) throw new IllegalArgumentException(key + " must be host:port");
    int port;
    try { port = Integer.parseInt(raw.substring(colon + 1)); }
    catch (NumberFormatException exception) { throw new IllegalArgumentException(key + " must be host:port, with a number for the port"); }
    if (port < 1 || port > 65535) throw new IllegalArgumentException(key + " port must be 1..65535");
    return new InetSocketAddress(raw.substring(0, colon), port);
  }
  /** Out of range, InetSocketAddress refused it as "port out of range:70000", naming no setting. */
  private static int port(Map<String, String> values, String key) {
    int port = integer(values, key);
    if (port < 1 || port > 65535) throw new IllegalArgumentException(key + " must be 1..65535");
    return port;
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
