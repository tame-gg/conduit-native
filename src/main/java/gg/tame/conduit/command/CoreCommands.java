package gg.tame.conduit.command;

import gg.tame.conduit.Conduit;
import gg.tame.conduit.api.server.ServerAvailability;
import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.metrics.ConduitMetrics;
import gg.tame.conduit.plugin.PluginCatalog;
import gg.tame.conduit.routing.ServerMatch;
import gg.tame.conduit.routing.ServerRegistry;
import gg.tame.conduit.runtime.ConduitRuntime;
import gg.tame.conduit.session.PlayerManager;
import gg.tame.conduit.session.TrackedPlayer;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/** Built-in Conduit commands. Clean, polished proxy UX — not a dashboard. */
public final class CoreCommands {
  private static final int MASS_SWITCH_CONCURRENCY = 16;
  /** Every /conduit subcommand, in help order. Drives both tab completion and the client graph. */
  public static final List<String> CONDUIT_SUBCOMMANDS = List.of("info", "plugins", "servers", "uptime",
      "dump", "heap", "reload", "metrics", "health", "maintenance", "drain", "undrain", "doctor",
      "diagnostics", "attack", "cache", "help");
  private static final Set<String> RESERVED = Set.of(
      "server", "send", "glist", "plist", "find", "alert", "ping", "hub", "gkick", "conduit");
  private CoreCommands() {}

  public static void register(CommandManager manager, ServerRegistry registry, PlayerManager players) {
    register(manager, registry, players, null);
  }

  public static void register(ConduitRuntime runtime) {
    register(runtime.commandManager(), runtime.selector().registry(), runtime.playerManager(), runtime);
  }

  public static void register(CommandManager manager, ServerRegistry registry, PlayerManager players, ConduitRuntime runtime) {
    manager.register(new RegisteredCommand("server", List.of(), Permissions.SERVER_USE,
        (source, arguments) -> server(source, registry, runtime, arguments),
        (source, arguments) -> completeServers(registry, arguments)));
    manager.register(new RegisteredCommand("send", List.of(), Permissions.SERVER_SEND,
        (source, arguments) -> send(source, registry, players, runtime, arguments),
        (source, arguments) -> completeSend(source, registry, players, arguments)));
    manager.register(new RegisteredCommand("glist", List.of(), Permissions.GLIST,
        (source, arguments) -> glist(source, players, registry), (source, arguments) -> List.of()));
    manager.register(new RegisteredCommand("plist", List.of(), Permissions.PLIST,
        (source, arguments) -> plist(source, players, registry, arguments),
        (source, arguments) -> completeServers(registry, arguments)));
    manager.register(new RegisteredCommand("find", List.of(), Permissions.FIND,
        (source, arguments) -> find(source, players, arguments),
        (source, arguments) -> completePlayers(players, arguments)));
    manager.register(new RegisteredCommand("alert", List.of(), Permissions.ALERT,
        (source, arguments) -> alert(source, players, arguments), (source, arguments) -> List.of()));
    manager.register(new RegisteredCommand("ping", List.of(), Permissions.PING,
        (source, arguments) -> ping(source), (source, arguments) -> List.of()));
    manager.register(new RegisteredCommand("hub", List.of(), Permissions.HUB,
        (source, arguments) -> hub(source, runtime), (source, arguments) -> List.of()));
    manager.register(new RegisteredCommand("gkick", List.of(), Permissions.GKICK,
        (source, arguments) -> gkick(source, players, arguments),
        (source, arguments) -> completePlayers(players, arguments)));
    manager.register(new RegisteredCommand("conduit", List.of(), Permissions.CONDUIT_INFO,
        (source, arguments) -> conduit(source, runtime, registry, arguments),
        (source, arguments) -> completeConduit(arguments)));
    for (String name : registry.names()) {
      String key = name.toLowerCase(Locale.ROOT);
      if (RESERVED.contains(key)) continue;
      String target = name;
      try {
        manager.register(new RegisteredCommand(key, List.of(), Permissions.SERVER_USE,
            (source, arguments) -> server(source, registry, runtime, List.of(target)),
            (source, arguments) -> List.of()));
      } catch (IllegalArgumentException ignored) { }
    }
  }

  private static void server(CommandSource source, ServerRegistry registry, ConduitRuntime runtime, List<String> arguments) {
    if (arguments.isEmpty()) {
      String current = source.currentBackend().isBlank() ? "none" : source.currentBackend();
      source.sendMessage(Text.of("You are currently connected to: ").color(Messages.LABEL)
          .append(Text.of(current).color(Messages.CURRENT).bold()));
      source.sendMessage(Text.empty());
      source.sendMessage(Text.of("Servers:").color(Messages.BRAND).bold());
      for (String name : registry.names()) {
        boolean here = name.equalsIgnoreCase(current);
        if (here) {
          source.sendMessage(Text.of("  ● ").color(Messages.CURRENT)
              .append(Text.of(name).color(Messages.CURRENT).bold()));
        } else {
          source.sendMessage(Text.of("  ○ ").color(Messages.OTHER)
              .append(Text.of(name).color(Messages.BODY)));
        }
      }
      return;
    }
    Optional<String> destination = resolveServer(source, registry, arguments.getFirst());
    if (destination.isEmpty()) return;
    String name = destination.get();
    if (name.equalsIgnoreCase(source.currentBackend())) {
      Messages.alreadyConnected(source, name);
      return;
    }
    if (!(source instanceof TrackedPlayer player)) {
      // Not "the server is unavailable" -- the console is not on a server. Saying otherwise sent
      // operators to check a backend that was fine.
      Messages.failure(source, "Only a player can switch servers. Use /send <player> " + name + ".");
      return;
    }
    if (runtime != null) {
      boolean drainBypass = source.hasPermission(Permissions.DRAIN_BYPASS) || source.hasPermission(Permissions.CONDUIT_ADMIN);
      if (runtime.health().isDraining(name) && !drainBypass) {
        Messages.failure(source, name + " is draining.");
        return;
      }
      if (runtime.health().snapshot(name).health() == gg.tame.conduit.health.BackendHealth.UNHEALTHY) {
        Messages.unavailable(source, name);
        return;
      }
      if (runtime.selector().status(name).availability() == ServerAvailability.OFFLINE) {
        Messages.unavailable(source, name);
        return;
      }
    }
    Messages.connecting(source, name);
    if (!player.transferTo(name)) Messages.unavailable(source, name);
  }

  private static void glist(CommandSource source, PlayerManager players, ServerRegistry registry) {
    source.sendMessage(Text.of("There are ").color(Messages.LABEL)
        .append(Text.of(String.valueOf(players.all().size())).color(Messages.BODY).bold())
        .append(Text.of(" player(s) online.").color(Messages.LABEL)));
    for (String server : registry.names()) {
      List<TrackedPlayer> on = players.byServer(server);
      if (on.isEmpty()) continue;
      source.sendMessage(Text.of("[" + server + "] ").color(Messages.BRAND)
          .append(Text.of("(" + on.size() + "): ").color(Messages.LABEL))
          .append(Text.of(joinNames(on)).color(Messages.BODY)));
    }
  }

  private static void plist(CommandSource source, PlayerManager players, ServerRegistry registry, List<String> arguments) {
    if (arguments.isEmpty()) {
      Messages.info(source, "Usage: /plist <server>");
      return;
    }
    Optional<String> server = resolveServer(source, registry, arguments.getFirst());
    if (server.isEmpty()) return;
    List<TrackedPlayer> on = players.byServer(server.get());
    source.sendMessage(Text.of("[" + server.get() + "] ").color(Messages.BRAND)
        .append(Text.of("(" + on.size() + "): ").color(Messages.LABEL))
        .append(Text.of(on.isEmpty() ? "(none)" : joinNames(on)).color(Messages.BODY)));
  }

  private static void find(CommandSource source, PlayerManager players, List<String> arguments) {
    if (arguments.isEmpty()) {
      Messages.info(source, "Usage: /find <player>");
      return;
    }
    Optional<TrackedPlayer> found = players.getByUsername(arguments.getFirst());
    if (found.isEmpty()) {
      Messages.failure(source, "Player " + arguments.getFirst() + " is not online.");
      return;
    }
    TrackedPlayer player = found.get();
    String server = player.currentBackend() == null || player.currentBackend().isBlank() ? "connecting" : player.currentBackend();
    source.sendMessage(Text.of(player.username()).color(Messages.BODY).bold()
        .append(Text.of(" is on ").color(Messages.LABEL))
        .append(Text.of(server).color(Messages.BRAND))
        .append(Text.of(".").color(Messages.LABEL)));
  }

  private static void alert(CommandSource source, PlayerManager players, List<String> arguments) {
    if (arguments.isEmpty()) {
      Messages.info(source, "Usage: /alert <message>");
      return;
    }
    Text line = Text.of("[Alert] ").color(Messages.WARN).bold()
        .append(Text.of(String.join(" ", arguments)).color(Messages.BODY));
    int sent = 0;
    for (TrackedPlayer player : players.all()) {
      if (player instanceof CommandSource commandSource) {
        commandSource.sendMessage(line);
        sent++;
      }
    }
    Messages.success(source, "Alert sent to " + sent + " player(s).");
  }

  private static void ping(CommandSource source) {
    source.sendMessage(Text.of("Connected through ").color(Messages.LABEL)
        .append(Text.of(Conduit.BRAND + " " + Conduit.VERSION).color(Messages.BRAND).bold())
        .append(Text.of(".").color(Messages.LABEL)));
  }

  private static void hub(CommandSource source, ConduitRuntime runtime) {
    if (!(source instanceof TrackedPlayer player)) {
      Messages.failure(source, "Only a player can use /hub.");
      return;
    }
    if (runtime == null) {
      Messages.failure(source, "Unable to resolve hub server.");
      return;
    }
    var candidates = runtime.selector().candidates();
    if (candidates.isEmpty()) {
      Messages.failure(source, "No servers configured.");
      return;
    }
    String hub = candidates.getFirst().name();
    if (hub.equalsIgnoreCase(source.currentBackend())) {
      Messages.alreadyConnected(source, hub);
      return;
    }
    Messages.connecting(source, hub);
    player.transferTo(hub);
  }

  private static void gkick(CommandSource source, PlayerManager players, List<String> arguments) {
    if (arguments.isEmpty()) {
      Messages.info(source, "Usage: /gkick <player> [reason]");
      return;
    }
    Optional<TrackedPlayer> found = players.getByUsername(arguments.getFirst());
    if (found.isEmpty()) {
      Messages.failure(source, "Player " + arguments.getFirst() + " is not online.");
      return;
    }
    TrackedPlayer target = found.get();
    String reason = arguments.size() == 1 ? "Kicked by an operator." : String.join(" ", arguments.subList(1, arguments.size()));
    if (target instanceof gg.tame.conduit.api.player.Player api) {
      api.disconnect(reason);
      Messages.success(source, "Kicked " + target.username() + ".");
      return;
    }
    Messages.failure(source, "Unable to kick " + target.username() + ".");
  }

  private static void conduit(CommandSource source, ConduitRuntime runtime, ServerRegistry registry, List<String> arguments) {
    if (arguments.isEmpty()) {
      info(source, runtime, registry);
      return;
    }
    switch (arguments.getFirst().toLowerCase(Locale.ROOT)) {
      case "info", "version" -> info(source, runtime, registry);
      case "plugins" -> plugins(source, runtime);
      case "servers" -> servers(source, runtime, registry);
      case "uptime" -> source.sendMessage(Text.of("Uptime: ").color(Messages.LABEL)
          .append(Text.of(formatUptime(runtime == null ? 0 : runtime.uptimeMillis())).color(Messages.BODY)));
      case "dump" -> dump(source, runtime, registry);
      case "heap" -> heap(source, runtime);
      case "reload" -> reload(source, runtime);
      case "metrics" -> source.sendMessage(Text.of(ConduitMetrics.current().snapshot().toString()).color(Messages.LABEL));
      case "health" -> health(source, runtime, registry);
      case "maintenance" -> maintenance(source, runtime, arguments.subList(1, arguments.size()));
      case "drain" -> drain(source, runtime, registry, arguments.subList(1, arguments.size()), true);
      case "undrain" -> drain(source, runtime, registry, arguments.subList(1, arguments.size()), false);
      case "doctor" -> doctor(source, runtime);
      case "diagnostics" -> diagnostics(source, runtime, registry);
      case "attack", "attackmode" -> attack(source, runtime, arguments.subList(1, arguments.size()));
      case "cache" -> cache(source, runtime, arguments.subList(1, arguments.size()));
      case "help" -> help(source);
      default -> Messages.info(source, "Unknown /conduit subcommand. Try /conduit help");
    }
  }

  private static void info(CommandSource source, ConduitRuntime runtime, ServerRegistry registry) {
    source.sendMessage(Text.of(Conduit.BRAND + " " + Conduit.VERSION).color(Messages.BRAND).bold());
    source.sendMessage(Text.empty());
    String current = source.currentBackend().isBlank() ? "none" : source.currentBackend();
    source.sendMessage(Text.of("Current server: ").color(Messages.LABEL)
        .append(Text.of(current).color(Messages.CURRENT).bold()));
    source.sendMessage(Text.of("Servers: ").color(Messages.LABEL)
        .append(Text.of(String.valueOf(registry.names().size())).color(Messages.BODY)));
    if (runtime != null) {
      source.sendMessage(Text.of("Players: ").color(Messages.LABEL)
          .append(Text.of(String.valueOf(ConduitMetrics.current().activePlayers())).color(Messages.BODY)));
      source.sendMessage(Text.of("Plugins: ").color(Messages.LABEL)
          .append(Text.of(String.valueOf(runtime.pluginCatalog().size())).color(Messages.BODY)));
    }
  }

  private static void servers(CommandSource source, ConduitRuntime runtime, ServerRegistry registry) {
    source.sendMessage(Text.of("Conduit Servers").color(Messages.BRAND).bold());
    source.sendMessage(Text.empty());
    String current = source.currentBackend();
    for (String name : registry.names()) {
      boolean here = name.equalsIgnoreCase(current);
      ServerAvailability availability = runtime == null ? ServerAvailability.UNKNOWN : runtime.selector().status(name).availability();
      String state = switch (availability) {
        case ONLINE -> "Online";
        case OFFLINE -> "Offline";
        case CONNECTING -> "Switching";
        case DEGRADED -> "Degraded";
        case UNKNOWN -> "Unknown";
      };
      var stateColor = availability == ServerAvailability.ONLINE ? Messages.OK
          : availability == ServerAvailability.OFFLINE ? Messages.BAD : Messages.WARN;
      if (here) {
        source.sendMessage(Text.of("● ").color(Messages.CURRENT).append(Text.of(name).color(Messages.CURRENT).bold()));
      } else {
        source.sendMessage(Text.of("○ ").color(Messages.OTHER).append(Text.of(name).color(Messages.BODY)));
      }
      source.sendMessage(Text.of("  ").append(Text.of(state).color(stateColor)));
      source.sendMessage(Text.empty());
    }
  }

  private static void plugins(CommandSource source, ConduitRuntime runtime) {
    if (!source.hasPermission(Permissions.PLUGINS) && !source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      Messages.permission(source);
      return;
    }
    PluginCatalog catalog = runtime == null ? null : runtime.pluginCatalog();
    if (catalog == null || catalog.size() == 0) {
      Messages.info(source, "No Conduit or Velocity plugins loaded.");
      return;
    }
    source.sendMessage(Text.of("Proxy plugins").color(Messages.BRAND).bold()
        .append(Text.of(" (" + catalog.size() + ")").color(Messages.LABEL)));
    for (PluginCatalog.Entry entry : catalog.all()) {
      source.sendMessage(Text.of("- ").color(Messages.OTHER).append(Text.of(entry.display()).color(Messages.BODY)));
    }
  }

  private static void help(CommandSource source) {
    source.sendMessage(Text.of("Conduit commands:").color(Messages.BRAND).bold());
    if (source.hasPermission(Permissions.SERVER_USE) || source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      source.sendMessage(Text.of("/server").color(Messages.BODY));
      source.sendMessage(Text.of("/server <server>").color(Messages.BODY));
    }
    if (source.hasPermission(Permissions.SERVER_SEND) || source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      source.sendMessage(Text.of("/send <player|server|current> <server>").color(Messages.BODY));
    }
    helpLine(source, Permissions.GLIST, "/glist");
    helpLine(source, Permissions.PLIST, "/plist <server>");
    helpLine(source, Permissions.FIND, "/find <player>");
    helpLine(source, Permissions.ALERT, "/alert <message>");
    helpLine(source, Permissions.PING, "/ping");
    helpLine(source, Permissions.GKICK, "/gkick <player> [reason]");
    if (source.hasPermission(Permissions.CONDUIT_INFO) || source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      source.sendMessage(Text.of("/conduit").color(Messages.BODY));
      source.sendMessage(Text.of("/conduit servers").color(Messages.BODY));
      source.sendMessage(Text.of("/conduit plugins").color(Messages.BODY));
      source.sendMessage(Text.of("/conduit health").color(Messages.BODY));
    }
    if (source.hasPermission(Permissions.MAINTENANCE) || source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      source.sendMessage(Text.of("/conduit maintenance <on|off|status>").color(Messages.BODY));
    }
    if (source.hasPermission(Permissions.DRAIN) || source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      source.sendMessage(Text.of("/conduit drain <server>").color(Messages.BODY));
      source.sendMessage(Text.of("/conduit undrain <server>").color(Messages.BODY));
    }
    if (source.hasPermission(Permissions.DOCTOR) || source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      source.sendMessage(Text.of("/conduit doctor").color(Messages.BODY));
    }
    if (source.hasPermission(Permissions.DIAGNOSTICS) || source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      source.sendMessage(Text.of("/conduit diagnostics").color(Messages.BODY));
    }
    if (source.hasPermission(Permissions.ATTACK) || source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      source.sendMessage(Text.of("/conduit attack <on|off|status>").color(Messages.BODY));
    }
    if (source.hasPermission(Permissions.CACHE) || source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      source.sendMessage(Text.of("/conduit cache invalidate <source>").color(Messages.BODY));
    }
    if (source.hasPermission(Permissions.RELOAD) || source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      source.sendMessage(Text.of("/conduit reload").color(Messages.BODY));
    }
    if (source.hasPermission(Permissions.HUB) || source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      source.sendMessage(Text.of("/hub").color(Messages.BODY));
    }
  }

  private static void helpLine(CommandSource source, String permission, String usage) {
    if (source.hasPermission(permission) || source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      source.sendMessage(Text.of(usage).color(Messages.BODY));
    }
  }

  private static void health(CommandSource source, ConduitRuntime runtime, ServerRegistry registry) {
    if (!source.hasPermission(Permissions.HEALTH) && !source.hasPermission(Permissions.CONDUIT_INFO)
        && !source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      Messages.permission(source);
      return;
    }
    if (runtime == null) {
      Messages.failure(source, "Runtime unavailable.");
      return;
    }
    source.sendMessage(Text.of("Conduit backend health").color(Messages.BRAND).bold());
    source.sendMessage(Text.empty());
    String current = source.currentBackend();
    for (String name : registry.names()) {
      var snap = runtime.health().snapshot(name);
      boolean here = name.equalsIgnoreCase(current);
      String label = switch (snap.health()) {
        case HEALTHY -> "Healthy";
        case UNHEALTHY -> "Unhealthy";
        case UNKNOWN -> "Unknown";
      };
      var color = snap.health() == gg.tame.conduit.health.BackendHealth.HEALTHY ? Messages.OK
          : snap.health() == gg.tame.conduit.health.BackendHealth.UNHEALTHY ? Messages.BAD : Messages.WARN;
      if (here) source.sendMessage(Text.of("● ").color(Messages.CURRENT).append(Text.of(name).color(Messages.CURRENT).bold()));
      else source.sendMessage(Text.of("○ ").color(Messages.OTHER).append(Text.of(name).color(Messages.BODY)));
      String detail = label + (snap.draining() ? " · draining" : "");
      if (snap.consecutiveFailures() > 0 || snap.consecutiveSuccesses() > 0) {
        detail += " · fail=" + snap.consecutiveFailures() + " ok=" + snap.consecutiveSuccesses();
      }
      source.sendMessage(Text.of("  ").append(Text.of(detail).color(color)));
      source.sendMessage(Text.empty());
    }
  }

  private static void maintenance(CommandSource source, ConduitRuntime runtime, List<String> arguments) {
    if (!source.hasPermission(Permissions.MAINTENANCE) && !source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      Messages.permission(source);
      return;
    }
    if (runtime == null) {
      Messages.failure(source, "Runtime unavailable.");
      return;
    }
    if (arguments.isEmpty() || arguments.getFirst().equalsIgnoreCase("status")) {
      boolean active = runtime.maintenance().isActive();
      source.sendMessage(Text.of("Maintenance: ").color(Messages.LABEL)
          .append(Text.of(active ? "ON" : "OFF").color(active ? Messages.WARN : Messages.OK).bold()));
      return;
    }
    try {
      if (arguments.getFirst().equalsIgnoreCase("on")) {
        if (!runtime.maintenance().featureEnabled()) {
          Messages.failure(source, "Maintenance feature is disabled in config.");
          return;
        }
        runtime.maintenance().enable();
        Messages.success(source, "Maintenance mode enabled.");
        return;
      }
      if (arguments.getFirst().equalsIgnoreCase("off")) {
        runtime.maintenance().disable();
        Messages.success(source, "Maintenance mode disabled.");
        return;
      }
    } catch (java.io.IOException exception) {
      Messages.failure(source, "Could not update maintenance flag.");
      return;
    }
    Messages.info(source, "Usage: /conduit maintenance <on|off|status>");
  }

  private static void attack(CommandSource source, ConduitRuntime runtime, List<String> arguments) {
    if (!source.hasPermission(Permissions.ATTACK) && !source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      Messages.permission(source);
      return;
    }
    if (runtime == null) {
      Messages.failure(source, "Runtime unavailable.");
      return;
    }
    if (arguments.isEmpty() || arguments.getFirst().equalsIgnoreCase("status")) {
      boolean active = runtime.security().attackMode().isActive();
      source.sendMessage(Text.of("Attack mode: ").color(Messages.LABEL)
          .append(Text.of(active ? "ON" : "OFF").color(active ? Messages.WARN : Messages.OK).bold()));
      return;
    }
    if (arguments.getFirst().equalsIgnoreCase("on")) {
      if (runtime.security().attackMode().enable()) Messages.success(source, "Conduit attack mode enabled.");
      else Messages.info(source, "Attack mode is already enabled.");
      return;
    }
    if (arguments.getFirst().equalsIgnoreCase("off")) {
      if (runtime.security().attackMode().disable()) Messages.success(source, "Conduit attack mode disabled.");
      else Messages.info(source, "Attack mode is already disabled.");
      return;
    }
    Messages.info(source, "Usage: /conduit attack <on|off|status>");
  }

  private static void cache(CommandSource source, ConduitRuntime runtime, List<String> arguments) {
    if (!source.hasPermission(Permissions.CACHE) && !source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      Messages.permission(source);
      return;
    }
    if (runtime == null) {
      Messages.failure(source, "Runtime unavailable.");
      return;
    }
    if (arguments.isEmpty() || !arguments.getFirst().equalsIgnoreCase("invalidate")) {
      Messages.info(source, "Usage: /conduit cache invalidate <source>");
      return;
    }
    if (arguments.size() < 2) {
      Messages.info(source, "Usage: /conduit cache invalidate <source>");
      return;
    }
    Optional<InetAddress> address = literalAddress(arguments.get(1));
    if (address.isEmpty()) {
      Messages.failure(source, "Not an IP address: " + arguments.get(1));
      return;
    }
    boolean removed = runtime.modded().invalidateCache(address.get());
    if (removed) Messages.success(source, "Mod handshake cache invalidated for source.");
    else Messages.info(source, "No cache entries for that source.");
  }

  /**
   * A literal address, or nothing. The cache is keyed by the address Conduit accepted a connection
   * from, so a host name is never the right answer here -- and passing one to
   * {@link InetAddress#getByName} would turn a command into a name lookup of an operator-supplied
   * string. IPv4 is parsed here and built with {@code getByAddress}, which never resolves; anything
   * containing a colon is an IPv6 literal, which getByName validates without resolving either.
   */
  private static Optional<InetAddress> literalAddress(String text) {
    try {
      if (text.indexOf(':') >= 0) return Optional.of(InetAddress.getByName(text));
      String[] parts = text.split("[.]", -1);
      if (parts.length != 4) return Optional.empty();
      byte[] octets = new byte[4];
      for (int index = 0; index < 4; index++) {
        String part = parts[index];
        if (part.isEmpty() || part.length() > 3) return Optional.empty();
        for (int digit = 0; digit < part.length(); digit++) {
          if (part.charAt(digit) < '0' || part.charAt(digit) > '9') return Optional.empty();
        }
        int octet = Integer.parseInt(part);
        if (octet > 255) return Optional.empty();
        octets[index] = (byte) octet;
      }
      return Optional.of(InetAddress.getByAddress(octets));
    } catch (java.net.UnknownHostException notALiteral) {
      return Optional.empty();
    }
  }

  private static void drain(CommandSource source, ConduitRuntime runtime, ServerRegistry registry, List<String> arguments, boolean enable) {
    if (!source.hasPermission(Permissions.DRAIN) && !source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      Messages.permission(source);
      return;
    }
    if (runtime == null) {
      Messages.failure(source, "Runtime unavailable.");
      return;
    }
    if (arguments.isEmpty()) {
      Messages.info(source, "Usage: /conduit " + (enable ? "drain" : "undrain") + " <server>");
      return;
    }
    Optional<String> server = resolveServer(source, registry, arguments.getFirst());
    if (server.isEmpty()) return;
    if (enable) {
      if (runtime.health().drain(server.get())) Messages.success(source, "Draining " + server.get() + ".");
      else Messages.info(source, server.get() + " is already draining.");
    } else {
      if (runtime.health().undrain(server.get())) Messages.success(source, "Undrained " + server.get() + ".");
      else Messages.info(source, server.get() + " was not draining.");
    }
  }

  private static void doctor(CommandSource source, ConduitRuntime runtime) {
    if (!source.hasPermission(Permissions.DOCTOR) && !source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      Messages.permission(source);
      return;
    }
    if (runtime == null) {
      Messages.failure(source, "Runtime unavailable.");
      return;
    }
    source.sendMessage(Text.of("Conduit doctor").color(Messages.BRAND).bold());
    for (var finding : gg.tame.conduit.ops.OpsDoctor.run(runtime)) {
      var color = switch (finding.severity()) {
        case OK -> Messages.OK;
        case WARNING -> Messages.WARN;
        case ERROR -> Messages.BAD;
      };
      source.sendMessage(Text.of(finding.severity().name() + " ").color(color)
          .append(Text.of(finding.message()).color(Messages.BODY)));
    }
  }

  private static void diagnostics(CommandSource source, ConduitRuntime runtime, ServerRegistry registry) {
    if (!source.hasPermission(Permissions.DIAGNOSTICS) && !source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      Messages.permission(source);
      return;
    }
    if (runtime == null) {
      Messages.failure(source, "Runtime unavailable.");
      return;
    }
    int healthy = 0;
    int unhealthy = 0;
    for (var snap : runtime.health().all()) {
      if (snap.health() == gg.tame.conduit.health.BackendHealth.HEALTHY) healthy++;
      if (snap.health() == gg.tame.conduit.health.BackendHealth.UNHEALTHY) unhealthy++;
    }
    source.sendMessage(Text.of("Conduit diagnostics").color(Messages.BRAND).bold());
    source.sendMessage(Text.of("Version: ").color(Messages.LABEL).append(Text.of(Conduit.VERSION).color(Messages.BODY)));
    source.sendMessage(Text.of("Uptime: ").color(Messages.LABEL).append(Text.of(formatUptime(runtime.uptimeMillis())).color(Messages.BODY)));
    source.sendMessage(Text.of("Players: ").color(Messages.LABEL)
        .append(Text.of(String.valueOf(ConduitMetrics.current().activePlayers())).color(Messages.BODY)));
    source.sendMessage(Text.of("Backends: ").color(Messages.LABEL)
        .append(Text.of(registry.names().size() + " (healthy=" + healthy + ", unhealthy=" + unhealthy + ")").color(Messages.BODY)));
    source.sendMessage(Text.of("Switches: ").color(Messages.LABEL)
        .append(Text.of(String.valueOf(ConduitMetrics.current().switches())).color(Messages.BODY)));
    source.sendMessage(Text.of("Failed switches: ").color(Messages.LABEL)
        .append(Text.of(String.valueOf(ConduitMetrics.current().failedSwitches())).color(Messages.BODY)));
    source.sendMessage(Text.of("Fallback events: ").color(Messages.LABEL)
        .append(Text.of(String.valueOf(ConduitMetrics.current().fallbackEvents())).color(Messages.BODY)));
    source.sendMessage(Text.of("Maintenance: ").color(Messages.LABEL)
        .append(Text.of(runtime.maintenance().isActive() ? "on" : "off").color(Messages.BODY)));
    source.sendMessage(Text.of("Attack mode: ").color(Messages.LABEL)
        .append(Text.of(runtime.security().attackMode().isActive() ? "on" : "off").color(Messages.BODY)));
    source.sendMessage(Text.of("Throttle drops: ").color(Messages.LABEL)
        .append(Text.of(String.valueOf(ConduitMetrics.current().connectionsThrottled())).color(Messages.BODY)));
    source.sendMessage(Text.of("Bot-filter blocks: ").color(Messages.LABEL)
        .append(Text.of(String.valueOf(ConduitMetrics.current().botFilterBlocks())).color(Messages.BODY)));
    source.sendMessage(Text.of("Channel-guard actions: ").color(Messages.LABEL)
        .append(Text.of(String.valueOf(ConduitMetrics.current().channelGuardActions())).color(Messages.BODY)));
    var modded = runtime.modded().settings();
    source.sendMessage(Text.of("Mod compatibility: ").color(Messages.LABEL)
        .append(Text.of(modded.enabled() ? "Enabled" : "Disabled").color(Messages.BODY)));
    source.sendMessage(Text.of("Known-packs limit: ").color(Messages.LABEL)
        .append(Text.of(String.valueOf(modded.knownPacksLimit())).color(Messages.BODY)));
    source.sendMessage(Text.of("Handshake cache entries: ").color(Messages.LABEL)
        .append(Text.of(String.valueOf(runtime.modded().cache().size())).color(Messages.BODY)));
    source.sendMessage(Text.of("Plugins: ").color(Messages.LABEL)
        .append(Text.of(String.valueOf(runtime.pluginCatalog().size())).color(Messages.BODY)));
  }

  private static void reload(CommandSource source, ConduitRuntime runtime) {
    if (!source.hasPermission(Permissions.RELOAD) && !source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      Messages.permission(source);
      return;
    }
    if (runtime == null) {
      Messages.failure(source, "Unable to reload.");
      return;
    }
    var result = runtime.reload();
    if (!result.applied() && result.error() != null) {
      Messages.failure(source, "Reload failed: " + result.error());
      return;
    }
    Messages.success(source, "Configuration reloaded.");
    if (!result.appliedLive().isEmpty()) {
      Messages.info(source, "Applied live: " + String.join(", ", result.appliedLive()));
    }
    if (!result.restartRequired().isEmpty()) {
      source.sendMessage(Text.of("Restart required:").color(Messages.WARN).bold());
      for (String item : result.restartRequired()) {
        source.sendMessage(Text.of("- ").color(Messages.OTHER).append(Text.of(item).color(Messages.BODY)));
      }
    }
  }

  private static void dump(CommandSource source, ConduitRuntime runtime, ServerRegistry registry) {
    if (!source.hasPermission(Permissions.DUMP) && !source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      Messages.permission(source);
      return;
    }
    try {
      Path file = diagnosticsDirectory(runtime).resolve("conduit-" + stamp() + ".txt");
      // Counters, versions and server names. No addresses, no config, no player data: this is the
      // one dump an operator is meant to be able to paste into an issue.
      StringBuilder body = new StringBuilder();
      body.append("Conduit ").append(Conduit.VERSION).append('\n');
      body.append("uptimeMs=").append(runtime == null ? 0 : runtime.uptimeMillis()).append('\n');
      body.append("metrics=").append(ConduitMetrics.current().snapshot()).append('\n');
      for (String name : registry.names()) body.append("server=").append(name).append('\n');
      Files.writeString(file, body.toString());
      Messages.success(source, "Wrote dump to " + file.toAbsolutePath());
      Messages.info(source, "Counters and server names only - no addresses, secrets, or player data.");
    } catch (IOException exception) {
      Messages.failure(source, "Dump failed.");
    }
  }

  private static void heap(CommandSource source, ConduitRuntime runtime) {
    if (!source.hasPermission(Permissions.HEAP) && !source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      Messages.permission(source);
      return;
    }
    // Said before the file exists, so the operator is told what they are about to create even if
    // the dump then fails. A committed .hprof is how a real session-service URL, player name and
    // auth token left this project once already.
    source.sendMessage(Text.of("A heap dump contains everything this process holds in memory: "
        + "forwarding secret, session tokens, player data. Treat the file as a credential - "
        + "do not commit it or attach it to an issue.").color(Messages.WARN));
    try {
      Path file = diagnosticsDirectory(runtime).resolve("heap-" + stamp() + ".hprof");
      Class<?> hotspot = Class.forName("com.sun.management.HotSpotDiagnosticMXBean");
      Object bean = ManagementFactory.newPlatformMXBeanProxy(
          ManagementFactory.getPlatformMBeanServer(),
          "com.sun.management:type=HotSpotDiagnostic",
          hotspot);
      hotspot.getMethod("dumpHeap", String.class, boolean.class).invoke(bean, file.toAbsolutePath().toString(), true);
      Messages.success(source, "Heap dump written to " + file.toAbsolutePath());
    } catch (ReflectiveOperationException | IOException exception) {
      Messages.failure(source, "Heap dump unavailable.");
    }
  }

  /**
   * Where both dumps go. Beside the proxy's own config rather than wherever the shell happened to
   * be when it was started, and always named {@code dumps} so the shipped .gitignore keeps matching
   * it at any depth. No command argument reaches this path: the file name is a timestamp.
   */
  private static Path diagnosticsDirectory(ConduitRuntime runtime) throws IOException {
    Path base = runtime == null ? Path.of(".") : runtime.configDirectory();
    Path dir = base.resolve("dumps").toAbsolutePath().normalize();
    Files.createDirectories(dir);
    try {
      // A heap dump is world-readable by default, and on shared hosting that is the whole secret.
      Files.setPosixFilePermissions(dir, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    } catch (UnsupportedOperationException | IOException windowsOrRestricted) {
      // Windows has no POSIX view; its inherited ACL already keeps this to the account.
    }
    return dir;
  }
  private static String stamp() { return Instant.now().toString().replace(':', '-'); }

  private static void send(CommandSource source, ServerRegistry registry, PlayerManager players, ConduitRuntime runtime, List<String> arguments) {
    if (arguments.size() != 2) {
      Messages.info(source, "Usage: /send <source-server> <destination-server>");
      Messages.info(source, "       /send current <destination-server>");
      Messages.info(source, "       /send <player> <destination-server>");
      return;
    }
    Optional<String> destination = resolveServer(source, registry, arguments.get(1));
    if (destination.isEmpty()) return;
    String dest = destination.get();
    if (runtime != null && runtime.selector().status(dest).availability() == ServerAvailability.OFFLINE) {
      Messages.failure(source, dest + " is unavailable.");
      return;
    }
    String from = arguments.getFirst();
    if (from.equalsIgnoreCase("current")) {
      if (!(source instanceof TrackedPlayer player)) {
        Messages.failure(source, "current can only be used by a player.");
        return;
      }
      if (dest.equalsIgnoreCase(source.currentBackend())) {
        source.sendMessage(Text.of(player.username()).color(Messages.BODY)
            .append(Text.of(" is already connected to ").color(Messages.LABEL))
            .append(Text.of(dest).color(Messages.CURRENT))
            .append(Text.of(".").color(Messages.LABEL)));
        return;
      }
      Messages.connecting(source, dest);
      if (player.transferTo(dest)) Messages.success(source, "Sent " + player.username() + " to " + dest + ".");
      else Messages.unavailable(source, dest);
      return;
    }
    Optional<TrackedPlayer> player = players.getByUsername(from);
    if (player.isPresent()) {
      TrackedPlayer target = player.get();
      // Moving yourself is /send's own permission. Only moving somebody else needs the other node.
      if (target != source
          && !source.hasPermission(Permissions.SERVER_SEND_OTHERS) && !source.hasPermission(Permissions.CONDUIT_ADMIN)) {
        Messages.permission(source);
        return;
      }
      if (dest.equalsIgnoreCase(target.currentBackend())) {
        source.sendMessage(Text.of(target.username()).color(Messages.BODY)
            .append(Text.of(" is already connected to ").color(Messages.LABEL))
            .append(Text.of(dest).color(Messages.CURRENT))
            .append(Text.of(".").color(Messages.LABEL)));
        return;
      }
      if (!target.transferTo(dest)) {
        Messages.failure(source, dest + " is unavailable.");
        return;
      }
      Messages.success(source, "Sent " + target.username() + " to " + dest + ".");
      return;
    }
    ServerMatch match = registry.resolve(from);
    if (match.kind() == ServerMatch.Kind.UNIQUE) {
      sendMass(source, players, match.server().orElseThrow().name(), dest);
      return;
    }
    if (match.kind() == ServerMatch.Kind.AMBIGUOUS) {
      resolveServer(source, registry, from);
      return;
    }
    Messages.failure(source, "Player " + from + " is not online.");
  }

  private static void sendMass(CommandSource source, PlayerManager players, String from, String dest) {
    if (!source.hasPermission(Permissions.SERVER_SEND_MASS) && !source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      Messages.permission(source);
      return;
    }
    List<TrackedPlayer> targets = players.byServer(from);
    if (targets.isEmpty()) {
      Messages.info(source, "No players are connected to " + from + ".");
      return;
    }
    AtomicInteger moved = new AtomicInteger();
    AtomicInteger failed = new AtomicInteger();
    Semaphore permits = new Semaphore(MASS_SWITCH_CONCURRENCY);
    List<Thread> workers = new ArrayList<>();
    for (TrackedPlayer target : targets) {
      if (dest.equalsIgnoreCase(target.currentBackend())) continue;
      workers.add(Thread.startVirtualThread(() -> {
        try {
          permits.acquire();
          if (target.transferTo(dest)) moved.incrementAndGet();
          else failed.incrementAndGet();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          failed.incrementAndGet();
        } finally {
          permits.release();
        }
      }));
    }
    for (Thread worker : workers) {
      try { worker.join(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    Messages.success(source, "Sent " + moved.get() + " players from " + from + " to " + dest + ".");
    if (failed.get() > 0) Messages.failure(source, failed.get() + " player(s) could not be moved.");
  }

  private static Optional<String> resolveServer(CommandSource source, ServerRegistry registry, String query) {
    ServerMatch match = registry.resolve(query);
    if (match.kind() == ServerMatch.Kind.NONE) {
      Messages.failure(source, "Unknown server: " + query);
      return Optional.empty();
    }
    if (match.kind() == ServerMatch.Kind.AMBIGUOUS) {
      Messages.info(source, "Multiple servers match:");
      for (String name : match.candidates()) source.sendMessage(Text.of(name).color(Messages.BODY));
      return Optional.empty();
    }
    return Optional.of(match.server().orElseThrow().name());
  }

  private static List<String> completeServers(ServerRegistry registry, List<String> arguments) {
    if (arguments.size() > 1) return List.of();
    return prefix(registry.names(), arguments.isEmpty() ? "" : arguments.getFirst());
  }

  private static List<String> completeSend(CommandSource source, ServerRegistry registry, PlayerManager players, List<String> arguments) {
    if (arguments.size() > 2) return List.of();
    if (arguments.size() <= 1) {
      LinkedHashSet<String> values = new LinkedHashSet<>();
      values.add("current");
      values.addAll(registry.names());
      values.addAll(players.onlineUsernames());
      return prefix(List.copyOf(values), arguments.isEmpty() ? "" : arguments.getFirst());
    }
    return prefix(registry.names(), arguments.get(1));
  }

  private static List<String> completePlayers(PlayerManager players, List<String> arguments) {
    if (arguments.size() > 1) return List.of();
    return prefix(players.onlineUsernames(), arguments.isEmpty() ? "" : arguments.getFirst());
  }

  private static List<String> completeConduit(List<String> arguments) {
    if (arguments.size() > 1) return List.of();
    return prefix(CONDUIT_SUBCOMMANDS, arguments.isEmpty() ? "" : arguments.getFirst());
  }

  private static List<String> prefix(List<String> values, String prefix) {
    String needle = prefix.toLowerCase(Locale.ROOT);
    List<String> names = new ArrayList<>();
    for (String value : values) {
      if (needle.isEmpty() || value.toLowerCase(Locale.ROOT).startsWith(needle)) names.add(value);
    }
    return names;
  }

  private static String joinNames(List<TrackedPlayer> players) {
    List<String> names = new ArrayList<>(players.size());
    for (TrackedPlayer player : players) names.add(player.username());
    return String.join(", ", names);
  }

  private static String formatUptime(long ms) {
    long seconds = ms / 1000;
    long minutes = seconds / 60;
    long hours = minutes / 60;
    long days = hours / 24;
    return days + "d " + (hours % 24) + "h " + (minutes % 60) + "m " + (seconds % 60) + "s";
  }
}
