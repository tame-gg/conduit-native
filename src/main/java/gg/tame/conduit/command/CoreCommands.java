package gg.tame.conduit.command;

import gg.tame.conduit.Conduit;
import gg.tame.conduit.api.server.ServerAvailability;
import gg.tame.conduit.api.server.ServerStatus;
import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.api.text.TextColor;
import gg.tame.conduit.metrics.ConduitMetrics;
import gg.tame.conduit.plugin.PluginCatalog;
import gg.tame.conduit.protocol.ProtocolVersion;
import gg.tame.conduit.routing.ServerMatch;
import gg.tame.conduit.routing.ServerRegistry;
import gg.tame.conduit.runtime.ConduitRuntime;
import gg.tame.conduit.session.PlayerManager;
import gg.tame.conduit.session.TrackedPlayer;
import java.io.IOException;
import java.lang.management.ManagementFactory;
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

/** Built-in operator/player commands for Conduit. */
public final class CoreCommands {
  private static final int MASS_SWITCH_CONCURRENCY = 16;
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
        (source, arguments) -> completeServers(registry, runtime, arguments)));
    manager.register(new RegisteredCommand("send", List.of(), Permissions.SERVER_SEND,
        (source, arguments) -> send(source, registry, players, runtime, arguments),
        (source, arguments) -> completeSend(source, registry, players, arguments)));
    manager.register(new RegisteredCommand("glist", List.of(), Permissions.GLIST,
        (source, arguments) -> glist(source, players, registry),
        (source, arguments) -> List.of()));
    manager.register(new RegisteredCommand("plist", List.of(), Permissions.PLIST,
        (source, arguments) -> plist(source, players, registry, arguments),
        (source, arguments) -> completeServers(registry, runtime, arguments)));
    manager.register(new RegisteredCommand("find", List.of(), Permissions.FIND,
        (source, arguments) -> find(source, players, arguments),
        (source, arguments) -> completePlayers(players, arguments)));
    manager.register(new RegisteredCommand("alert", List.of(), Permissions.ALERT,
        (source, arguments) -> alert(source, players, arguments),
        (source, arguments) -> List.of()));
    manager.register(new RegisteredCommand("ping", List.of(), Permissions.PING,
        (source, arguments) -> ping(source),
        (source, arguments) -> List.of()));
    manager.register(new RegisteredCommand("hub", List.of(), Permissions.HUB,
        (source, arguments) -> hub(source, runtime),
        (source, arguments) -> List.of()));
    manager.register(new RegisteredCommand("gkick", List.of(), Permissions.GKICK,
        (source, arguments) -> gkick(source, players, arguments),
        (source, arguments) -> completePlayers(players, arguments)));
    manager.register(new RegisteredCommand("conduit", List.of(), Permissions.CONDUIT_INFO,
        (source, arguments) -> conduit(source, runtime, registry, players, arguments),
        (source, arguments) -> completeConduit(source, arguments)));
    // Slash-server aliases: /lobby → /server lobby (Conduit-specific convenience).
    for (String name : registry.names()) {
      String key = name.toLowerCase(Locale.ROOT);
      if (RESERVED.contains(key)) continue;
      String target = name;
      try {
        manager.register(new RegisteredCommand(key, List.of(), Permissions.SERVER_USE,
            (source, arguments) -> server(source, registry, runtime, List.of(target)),
            (source, arguments) -> List.of()));
      } catch (IllegalArgumentException ignored) {
        // Already registered by a plugin or earlier pass.
      }
    }
  }

  private static void server(CommandSource source, ServerRegistry registry, ConduitRuntime runtime, List<String> arguments) {
    if (arguments.isEmpty()) {
      listServers(source, registry, runtime);
      return;
    }
    Optional<String> destination = resolveServer(source, registry, arguments.getFirst());
    if (destination.isEmpty()) return;
    String name = destination.get();
    String display = ConduitUi.titleCase(name);
    if (name.equalsIgnoreCase(source.currentBackend())) {
      ConduitUi.brandLine(source);
      source.sendMessage(Text.of("You are already connected to ").color(ConduitUi.MUTED)
          .append(Text.of(display).color(ConduitUi.CURRENT).bold())
          .append(Text.of(".").color(ConduitUi.MUTED)));
      return;
    }
    if (!(source instanceof TrackedPlayer player)) {
      ConduitUi.failure(source, "Unable to connect to " + display + ".", "This command can only be used by a player.");
      return;
    }
    if (runtime != null) {
      ServerStatus status = runtime.selector().status(name);
      if (status.availability() == ServerAvailability.OFFLINE) {
        ConduitUi.brandLine(source);
        ConduitUi.failure(source, "Unable to connect to " + display + ".", "The server is currently unavailable.");
        return;
      }
    }
    ConduitUi.connecting(source, display);
    player.transferTo(name);
  }

  private static void listServers(CommandSource source, ServerRegistry registry, ConduitRuntime runtime) {
    ConduitUi.panelOpen(source, "CONDUIT");
    ConduitUi.blank(source);
    String current = source.currentBackend();
    source.sendMessage(Text.of("  You are connected to").color(ConduitUi.MUTED));
    if (current == null || current.isBlank()) {
      source.sendMessage(Text.of("  ● ").color(ConduitUi.WARN).append(Text.of("None").color(ConduitUi.TITLE).bold()));
    } else {
      source.sendMessage(Text.of("  ● ").color(ConduitUi.CURRENT)
          .append(Text.of(ConduitUi.titleCase(current)).color(ConduitUi.CURRENT).bold()));
    }
    ConduitUi.blank(source);
    source.sendMessage(Text.of("  Available Servers").color(ConduitUi.LABEL).bold());
    ConduitUi.blank(source);
    for (String name : registry.names()) {
      ServerStatus status = runtime == null ? ServerStatus.unknown(name) : runtime.selector().status(name);
      boolean here = name.equalsIgnoreCase(current);
      TextColor dot = switch (status.availability()) {
        case ONLINE -> ConduitUi.ONLINE;
        case OFFLINE -> ConduitUi.OFFLINE;
        case CONNECTING -> ConduitUi.WARN;
        default -> ConduitUi.MUTED;
      };
      String state = switch (status.availability()) {
        case ONLINE -> "ONLINE";
        case OFFLINE -> "OFFLINE";
        case CONNECTING -> "SWITCHING";
        case DEGRADED -> "DEGRADED";
        case UNKNOWN -> "UNKNOWN";
      };
      TextColor stateColor = status.online() ? ConduitUi.ONLINE : (status.availability() == ServerAvailability.OFFLINE ? ConduitUi.BAD : ConduitUi.MUTED);
      String display = ConduitUi.titleCase(name);
      Text hover = Text.of("Server: ").color(ConduitUi.MUTED).append(Text.of(name).color(ConduitUi.TITLE))
          .append(Text.of("\nStatus: ").color(ConduitUi.MUTED)).append(Text.of(state).color(stateColor));
      if (status.displayVersion().isPresent()) {
        hover = hover.append(Text.of("\nVersion: ").color(ConduitUi.MUTED)
            .append(Text.of(status.displayVersion().get()).color(ConduitUi.TITLE)));
      }
      if (status.onlinePlayers().isPresent() && status.maxPlayers().isPresent()) {
        hover = hover.append(Text.of("\nPlayers: ").color(ConduitUi.MUTED)
            .append(Text.of(status.onlinePlayers().getAsInt() + "/" + status.maxPlayers().getAsInt()).color(ConduitUi.TITLE)));
      }
      if (here) hover = hover.append(Text.of("\nYou are here").color(ConduitUi.CURRENT));
      else hover = hover.append(Text.of("\nClick to connect").color(ConduitUi.ACCENT));

      Text line = Text.of("  ● ").color(dot)
          .append(Text.of(padRight(display, 12)).color(here ? ConduitUi.CURRENT : ConduitUi.TITLE).bold()
              .clickRun("/server " + name)
              .hover(hover))
          .append(Text.of(state).color(stateColor));
      if (here) line = line.append(Text.of("  ← you").color(ConduitUi.MUTED));
      source.sendMessage(line);
    }
    ConduitUi.blank(source);
    ConduitUi.muted(source, "  Click a server to connect.");
    ConduitUi.panelClose(source);
  }

  private static void plugins(CommandSource source, ConduitRuntime runtime) {
    if (!source.hasPermission(Permissions.PLUGINS) && !source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      ConduitUi.permissionDenied(source, "view proxy plugins");
      return;
    }
    PluginCatalog catalog = runtime == null ? null : runtime.pluginCatalog();
    ConduitUi.panelOpen(source, "PROXY PLUGINS");
    if (catalog == null || catalog.size() == 0) {
      ConduitUi.blank(source);
      ConduitUi.muted(source, "  No Conduit or Velocity plugins loaded.");
      ConduitUi.muted(source, "  Backend Paper/Spigot plugins are not listed here.");
      ConduitUi.blank(source);
      ConduitUi.panelClose(source);
      return;
    }
    ConduitUi.blank(source);
    source.sendMessage(Text.of("  ").append(Text.of(catalog.size() + " loaded").color(ConduitUi.TITLE))
        .append(Text.of("  ·  Conduit / Velocity only").color(ConduitUi.MUTED)));
    ConduitUi.blank(source);
    for (PluginCatalog.Entry entry : catalog.all()) {
      TextColor tag = entry.kind() == PluginCatalog.Kind.VELOCITY ? ConduitUi.WARN : ConduitUi.ACCENT;
      String kind = entry.kind() == PluginCatalog.Kind.VELOCITY ? "velocity" : "conduit";
      source.sendMessage(Text.of("  ● ").color(tag)
          .append(Text.of(entry.name()).color(ConduitUi.TITLE).bold())
          .append(Text.of(" " + entry.version()).color(ConduitUi.MUTED))
          .append(Text.of(" [" + kind + "]").color(tag)));
    }
    ConduitUi.blank(source);
    ConduitUi.panelClose(source);
  }

  private static void glist(CommandSource source, PlayerManager players, ServerRegistry registry) {
    ConduitUi.panelOpen(source, "NETWORK");
    ConduitUi.blank(source);
    source.sendMessage(Text.of("  ").append(Text.of(players.all().size() + " player(s) online").color(ConduitUi.TITLE).bold()));
    ConduitUi.blank(source);
    for (String server : registry.names()) {
      List<TrackedPlayer> on = players.byServer(server);
      if (on.isEmpty()) continue;
      source.sendMessage(Text.of("  [" + ConduitUi.titleCase(server) + "] ").color(ConduitUi.ACCENT)
          .append(Text.of("(" + on.size() + ") ").color(ConduitUi.MUTED))
          .append(Text.of(joinNames(on)).color(ConduitUi.TITLE)));
    }
    List<TrackedPlayer> unknown = new ArrayList<>();
    for (TrackedPlayer player : players.all()) {
      if (player.currentBackend() == null || player.currentBackend().isBlank()) unknown.add(player);
    }
    if (!unknown.isEmpty()) {
      source.sendMessage(Text.of("  [Connecting] ").color(ConduitUi.WARN)
          .append(Text.of("(" + unknown.size() + ") ").color(ConduitUi.MUTED))
          .append(Text.of(joinNames(unknown)).color(ConduitUi.TITLE)));
    }
    ConduitUi.blank(source);
    ConduitUi.panelClose(source);
  }

  private static void plist(CommandSource source, PlayerManager players, ServerRegistry registry, List<String> arguments) {
    if (arguments.isEmpty()) {
      ConduitUi.muted(source, "Usage: /plist <server>");
      return;
    }
    Optional<String> server = resolveServer(source, registry, arguments.getFirst());
    if (server.isEmpty()) return;
    List<TrackedPlayer> on = players.byServer(server.get());
    ConduitUi.brandLine(source);
    source.sendMessage(Text.of("[" + ConduitUi.titleCase(server.get()) + "] ").color(ConduitUi.ACCENT)
        .append(Text.of("(" + on.size() + "): ").color(ConduitUi.MUTED))
        .append(Text.of(on.isEmpty() ? "(none)" : joinNames(on)).color(ConduitUi.TITLE)));
  }

  private static void find(CommandSource source, PlayerManager players, List<String> arguments) {
    if (arguments.isEmpty()) {
      ConduitUi.muted(source, "Usage: /find <player>");
      return;
    }
    Optional<TrackedPlayer> found = players.getByUsername(arguments.getFirst());
    if (found.isEmpty()) {
      ConduitUi.failure(source, "Player " + arguments.getFirst() + " is not online.");
      return;
    }
    TrackedPlayer player = found.get();
    String server = player.currentBackend() == null || player.currentBackend().isBlank() ? "connecting" : player.currentBackend();
    ConduitUi.brandLine(source);
    source.sendMessage(Text.of(player.username()).color(ConduitUi.TITLE).bold()
        .append(Text.of(" is on ").color(ConduitUi.MUTED))
        .append(Text.of(ConduitUi.titleCase(server)).color(ConduitUi.ACCENT)));
  }

  private static void alert(CommandSource source, PlayerManager players, List<String> arguments) {
    if (arguments.isEmpty()) {
      ConduitUi.muted(source, "Usage: /alert <message>");
      return;
    }
    String body = String.join(" ", arguments);
    Text line = Text.of("[Alert] ").color(ConduitUi.WARN).bold()
        .append(Text.of(body).color(ConduitUi.TITLE));
    int sent = 0;
    for (TrackedPlayer player : players.all()) {
      if (player instanceof CommandSource commandSource) {
        commandSource.sendMessage(line);
        sent++;
      }
    }
    ConduitUi.success(source, "Alert sent to " + sent + " player(s).");
  }

  private static void ping(CommandSource source) {
    ConduitUi.brandLine(source);
    source.sendMessage(Text.of("Connected through ").color(ConduitUi.MUTED)
        .append(Text.of(Conduit.BRAND + " " + Conduit.VERSION).color(ConduitUi.ACCENT).bold()));
    ConduitUi.muted(source, "Per-player latency is not tracked yet.");
  }

  private static void hub(CommandSource source, ConduitRuntime runtime) {
    if (!(source instanceof TrackedPlayer player)) {
      ConduitUi.failure(source, "hub can only be used by a player.");
      return;
    }
    if (runtime == null) {
      ConduitUi.failure(source, "Unable to resolve hub server.");
      return;
    }
    var candidates = runtime.selector().candidates();
    if (candidates.isEmpty()) {
      ConduitUi.failure(source, "No servers configured.");
      return;
    }
    String hub = candidates.getFirst().name();
    String display = ConduitUi.titleCase(hub);
    if (hub.equalsIgnoreCase(source.currentBackend())) {
      source.sendMessage(Text.of("You are already connected to ").color(ConduitUi.MUTED)
          .append(Text.of(display).color(ConduitUi.CURRENT).bold())
          .append(Text.of(".").color(ConduitUi.MUTED)));
      return;
    }
    ConduitUi.connecting(source, display);
    player.transferTo(hub);
  }

  private static void gkick(CommandSource source, PlayerManager players, List<String> arguments) {
    if (arguments.isEmpty()) {
      ConduitUi.muted(source, "Usage: /gkick <player> [reason]");
      return;
    }
    Optional<TrackedPlayer> found = players.getByUsername(arguments.getFirst());
    if (found.isEmpty()) {
      ConduitUi.failure(source, "Player " + arguments.getFirst() + " is not online.");
      return;
    }
    TrackedPlayer target = found.get();
    String reason = arguments.size() == 1 ? "Kicked by an operator." : String.join(" ", arguments.subList(1, arguments.size()));
    if (target instanceof gg.tame.conduit.api.player.Player api) {
      api.disconnect(reason);
      ConduitUi.success(source, "Kicked " + target.username() + ".");
      return;
    }
    ConduitUi.failure(source, "Unable to kick " + target.username() + ".");
  }

  private static void conduit(CommandSource source, ConduitRuntime runtime, ServerRegistry registry, PlayerManager players, List<String> arguments) {
    if (arguments.isEmpty()) {
      info(source, runtime, registry, players);
      return;
    }
    String sub = arguments.getFirst().toLowerCase(Locale.ROOT);
    switch (sub) {
      case "info", "version" -> info(source, runtime, registry, players);
      case "plugins" -> plugins(source, runtime);
      case "servers" -> conduitServers(source, runtime, registry, players);
      case "uptime" -> uptime(source, runtime);
      case "dump" -> dump(source, runtime, registry);
      case "heap" -> heap(source);
      case "reload" -> reload(source, runtime);
      case "metrics" -> metrics(source);
      case "health" -> health(source, runtime, registry);
      case "help" -> conduitHelp(source);
      default -> ConduitUi.muted(source, "Unknown /conduit subcommand. Try /conduit help");
    }
  }

  private static void info(CommandSource source, ConduitRuntime runtime, ServerRegistry registry, PlayerManager players) {
    ConduitUi.panelOpen(source, "CONDUIT");
    ConduitUi.blank(source);
    ConduitUi.row(source, "Proxy", Conduit.BRAND);
    ConduitUi.row(source, "Version", Conduit.VERSION);
    ConduitUi.row(source, "API", String.valueOf(Conduit.API_VERSION));
    String current = source.currentBackend();
    ConduitUi.row(source, "Current Server", current == null || current.isBlank() ? "—" : ConduitUi.titleCase(current));
    ConduitUi.row(source, "Servers", String.valueOf(registry.names().size()));
    ConduitUi.row(source, "Players", String.valueOf(ConduitMetrics.current().activePlayers()));
    if (runtime != null) {
      ConduitUi.row(source, "Plugins", String.valueOf(runtime.pluginCatalog().size()));
      ConduitUi.row(source, "Uptime", formatUptime(runtime.uptimeMillis()));
      long online = runtime.selector().allStatuses().stream().filter(ServerStatus::online).count();
      ConduitUi.row(source, "Status", online + "/" + registry.names().size() + " backends online");
      String protocols = runtime.selector().allStatuses().stream()
          .filter(ServerStatus::online)
          .map(status -> status.protocol().isPresent() ? ProtocolVersion.display(status.protocol().getAsInt()) : "?")
          .distinct()
          .reduce((a, b) -> a + " / " + b)
          .orElse("—");
      ConduitUi.row(source, "Protocols", protocols);
    }
    ConduitUi.blank(source);
    ConduitUi.muted(source, "  /conduit help for commands");
    ConduitUi.panelClose(source);
  }

  private static void conduitServers(CommandSource source, ConduitRuntime runtime, ServerRegistry registry, PlayerManager players) {
    ConduitUi.panelOpen(source, "CONDUIT SERVERS");
    ConduitUi.blank(source);
    for (String name : registry.names()) {
      ServerStatus status = runtime == null ? ServerStatus.unknown(name) : runtime.selector().status(name);
      int known = players.byServer(name).size();
      TextColor dot = status.online() ? ConduitUi.ONLINE : (status.availability() == ServerAvailability.OFFLINE ? ConduitUi.BAD : ConduitUi.MUTED);
      String state = status.availability().name();
      source.sendMessage(Text.of("  " + ConduitUi.titleCase(name)).color(ConduitUi.TITLE).bold());
      Text line = Text.of("  ● ").color(dot).append(Text.of(state + "     ").color(dot));
      if (status.onlinePlayers().isPresent()) {
        String count = status.onlinePlayers().getAsInt()
            + (status.maxPlayers().isPresent() ? "/" + status.maxPlayers().getAsInt() : "");
        line = line.append(Text.of("Players: ").color(ConduitUi.MUTED)).append(Text.of(count).color(ConduitUi.TITLE));
      } else {
        line = line.append(Text.of("Players: ").color(ConduitUi.MUTED)).append(Text.of(String.valueOf(known)).color(ConduitUi.TITLE))
            .append(Text.of(" (proxy)").color(ConduitUi.MUTED));
      }
      source.sendMessage(line);
      ConduitUi.blank(source);
    }
    ConduitUi.panelClose(source);
  }

  private static void conduitHelp(CommandSource source) {
    ConduitUi.panelOpen(source, "CONDUIT HELP");
    ConduitUi.blank(source);
    helpLine(source, Permissions.SERVER_USE, "/server", "View available servers");
    helpLine(source, Permissions.SERVER_USE, "/server <name>", "Connect to another server");
    helpLine(source, Permissions.SERVER_SEND, "/send <player|server|current> <server>", "Move players between servers");
    helpLine(source, Permissions.CONDUIT_INFO, "/conduit", "View proxy information");
    helpLine(source, Permissions.CONDUIT_INFO, "/conduit servers", "View server status");
    helpLine(source, Permissions.PLUGINS, "/conduit plugins", "List Conduit / Velocity proxy plugins");
    helpLine(source, Permissions.GLIST, "/glist", "List players across the network");
    helpLine(source, Permissions.FIND, "/find <player>", "Find which server a player is on");
    helpLine(source, Permissions.HUB, "/hub", "Return to the hub server");
    helpLine(source, Permissions.ALERT, "/alert <message>", "Broadcast a network alert");
    ConduitUi.blank(source);
    ConduitUi.panelClose(source);
  }

  private static void helpLine(CommandSource source, String permission, String command, String description) {
    if (permission != null && !permission.isBlank() && !source.hasPermission(permission)
        && !source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      return;
    }
    source.sendMessage(Text.of("  " + command).color(ConduitUi.ACCENT).bold()
        .clickRun(command.contains("<") ? command.split(" ")[0] : command)
        .hover(Text.of(description).color(ConduitUi.MUTED)));
    source.sendMessage(Text.of("      " + description).color(ConduitUi.MUTED));
  }

  private static void uptime(CommandSource source, ConduitRuntime runtime) {
    ConduitUi.brandLine(source);
    ConduitUi.row(source, "Uptime", formatUptime(runtime == null ? 0 : runtime.uptimeMillis()));
  }

  private static void metrics(CommandSource source) {
    ConduitUi.brandLine(source);
    source.sendMessage(Text.of(ConduitMetrics.current().snapshot().toString()).color(ConduitUi.MUTED));
  }

  private static void health(CommandSource source, ConduitRuntime runtime, ServerRegistry registry) {
    if (runtime == null) {
      ConduitUi.failure(source, "Runtime unavailable.");
      return;
    }
    ConduitUi.panelOpen(source, "HEALTH");
    ConduitUi.blank(source);
    for (String name : registry.names()) {
      ServerStatus status = runtime.selector().status(name);
      TextColor color = status.online() ? ConduitUi.ONLINE : ConduitUi.BAD;
      String detail = status.online()
          ? "protocol " + (status.protocol().isPresent() ? status.protocol().getAsInt() : "?")
          : "no status response";
      source.sendMessage(Text.of("  ● ").color(color)
          .append(Text.of(ConduitUi.titleCase(name) + ": ").color(ConduitUi.TITLE))
          .append(Text.of(status.availability().name().toLowerCase(Locale.ROOT)).color(color))
          .append(Text.of(" (" + detail + ")").color(ConduitUi.MUTED)));
    }
    ConduitUi.blank(source);
    ConduitUi.panelClose(source);
  }

  private static void reload(CommandSource source, ConduitRuntime runtime) {
    if (!source.hasPermission(Permissions.RELOAD) && !source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      ConduitUi.permissionDenied(source, "reload backend status");
      return;
    }
    if (runtime == null) {
      ConduitUi.failure(source, "Unable to reload.");
      return;
    }
    runtime.selector().probeAll();
    ConduitUi.success(source, "Re-probed configured backends.");
    ConduitUi.muted(source, "Full conduit.toml reload is not implemented yet.");
  }

  private static void dump(CommandSource source, ConduitRuntime runtime, ServerRegistry registry) {
    if (!source.hasPermission(Permissions.DUMP) && !source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      ConduitUi.permissionDenied(source, "write diagnostic dumps");
      return;
    }
    try {
      Path dir = Path.of("dumps");
      Files.createDirectories(dir);
      Path file = dir.resolve("conduit-" + Instant.now().toString().replace(':', '-') + ".txt");
      StringBuilder body = new StringBuilder();
      body.append("Conduit dump\n");
      body.append("version=").append(Conduit.VERSION).append(" api=").append(Conduit.API_VERSION).append('\n');
      body.append("java=").append(System.getProperty("java.version")).append('\n');
      body.append("uptimeMs=").append(runtime == null ? 0 : runtime.uptimeMillis()).append('\n');
      body.append("metrics=").append(ConduitMetrics.current().snapshot()).append('\n');
      body.append("servers=\n");
      for (String name : registry.names()) {
        ServerStatus status = runtime == null ? ServerStatus.unknown(name) : runtime.selector().status(name);
        body.append("  - ").append(name).append(" ").append(status.availability()).append('\n');
      }
      body.append("plugins=\n");
      if (runtime != null) {
        for (PluginCatalog.Entry entry : runtime.pluginCatalog().all()) {
          body.append("  - ").append(entry.display()).append('\n');
        }
      }
      body.append("players=").append(ConduitMetrics.current().activePlayers()).append('\n');
      Files.writeString(file, body.toString());
      ConduitUi.success(source, "Wrote dump to " + file.toAbsolutePath());
    } catch (IOException exception) {
      ConduitUi.failure(source, "Dump failed.", "Unable to write diagnostics file.");
    }
  }

  private static void heap(CommandSource source) {
    if (!source.hasPermission(Permissions.HEAP) && !source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      ConduitUi.permissionDenied(source, "request a heap dump");
      return;
    }
    try {
      Path dir = Path.of("dumps");
      Files.createDirectories(dir);
      Path file = dir.resolve("heap-" + Instant.now().toString().replace(':', '-') + ".hprof");
      Class<?> hotspot = Class.forName("com.sun.management.HotSpotDiagnosticMXBean");
      Object bean = ManagementFactory.newPlatformMXBeanProxy(
          ManagementFactory.getPlatformMBeanServer(),
          "com.sun.management:type=HotSpotDiagnostic",
          hotspot);
      hotspot.getMethod("dumpHeap", String.class, boolean.class).invoke(bean, file.toAbsolutePath().toString(), true);
      ConduitUi.success(source, "Heap dump written to " + file.toAbsolutePath());
    } catch (ReflectiveOperationException | IOException exception) {
      ConduitUi.failure(source, "Heap dump unavailable.", "The runtime does not support heap dumps.");
    }
  }

  private static void send(CommandSource source, ServerRegistry registry, PlayerManager players, ConduitRuntime runtime, List<String> arguments) {
    if (arguments.size() != 2) {
      ConduitUi.brandLine(source);
      ConduitUi.muted(source, "Usage: /send <source-server> <destination-server>");
      ConduitUi.muted(source, "       /send current <destination-server>");
      ConduitUi.muted(source, "       /send <player> <destination-server>");
      return;
    }
    Optional<String> destination = resolveServer(source, registry, arguments.get(1));
    if (destination.isEmpty()) return;
    String dest = destination.get();
    if (runtime != null && runtime.selector().status(dest).availability() == ServerAvailability.OFFLINE) {
      ConduitUi.failure(source, "Unable to send players.", "Target server is unavailable.");
      return;
    }
    String from = arguments.getFirst();
    if (from.equalsIgnoreCase("current")) {
      sendCurrent(source, dest);
      return;
    }
    Optional<TrackedPlayer> player = players.getByUsername(from);
    if (player.isPresent()) {
      sendPlayer(source, player.get(), dest);
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
    ConduitUi.failure(source, "Player " + from + " is not online.");
  }

  private static void sendCurrent(CommandSource source, String dest) {
    if (!(source instanceof TrackedPlayer player)) {
      ConduitUi.failure(source, "current can only be used by a player.");
      return;
    }
    String display = ConduitUi.titleCase(dest);
    if (dest.equalsIgnoreCase(source.currentBackend())) {
      source.sendMessage(Text.of(player.username()).color(ConduitUi.TITLE)
          .append(Text.of(" is already connected to ").color(ConduitUi.MUTED))
          .append(Text.of(display).color(ConduitUi.CURRENT)));
      return;
    }
    String fromDisplay = ConduitUi.titleCase(source.currentBackend().isBlank() ? "—" : source.currentBackend());
    ConduitUi.connecting(source, display);
    if (player.transferTo(dest)) {
      // Success chat comes from PlayerSession; also show send summary for the operator.
      ConduitUi.brandLine(source);
      ConduitUi.success(source, player.username());
      source.sendMessage(Text.of("  ").append(Text.of(fromDisplay).color(ConduitUi.MUTED))
          .append(Text.of(" → ").color(ConduitUi.ACCENT))
          .append(Text.of(display).color(ConduitUi.TITLE)));
    }
  }

  private static void sendPlayer(CommandSource source, TrackedPlayer target, String dest) {
    if (!source.hasPermission(Permissions.SERVER_SEND_OTHERS) && !source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      ConduitUi.permissionDenied(source, "move players between servers");
      return;
    }
    String display = ConduitUi.titleCase(dest);
    String fromDisplay = ConduitUi.titleCase(target.currentBackend() == null || target.currentBackend().isBlank() ? "—" : target.currentBackend());
    if (dest.equalsIgnoreCase(target.currentBackend())) {
      source.sendMessage(Text.of(target.username()).color(ConduitUi.TITLE)
          .append(Text.of(" is already connected to ").color(ConduitUi.MUTED))
          .append(Text.of(display).color(ConduitUi.CURRENT)));
      return;
    }
    if (!target.transferTo(dest)) {
      ConduitUi.failure(source, "Unable to move " + target.username(), "Target server is unavailable.");
      return;
    }
    ConduitUi.brandLine(source);
    ConduitUi.success(source, target.username());
    source.sendMessage(Text.of("  ").append(Text.of(fromDisplay).color(ConduitUi.MUTED))
        .append(Text.of(" → ").color(ConduitUi.ACCENT))
        .append(Text.of(display).color(ConduitUi.TITLE)));
  }

  private static void sendMass(CommandSource source, PlayerManager players, String from, String dest) {
    if (!source.hasPermission(Permissions.SERVER_SEND_MASS) && !source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      ConduitUi.permissionDenied(source, "move players between servers");
      return;
    }
    List<TrackedPlayer> targets = players.byServer(from);
    if (targets.isEmpty()) {
      ConduitUi.muted(source, "No players are connected to " + ConduitUi.titleCase(from) + ".");
      return;
    }
    String fromDisplay = ConduitUi.titleCase(from);
    String destDisplay = ConduitUi.titleCase(dest);
    ConduitUi.brandLine(source);
    source.sendMessage(Text.of("Sending players ").color(ConduitUi.MUTED)
        .append(Text.of(fromDisplay).color(ConduitUi.TITLE))
        .append(Text.of(" → ").color(ConduitUi.ACCENT))
        .append(Text.of(destDisplay).color(ConduitUi.TITLE))
        .append(Text.of("...").color(ConduitUi.MUTED)));
    AtomicInteger moved = new AtomicInteger();
    AtomicInteger failed = new AtomicInteger();
    Semaphore permits = new Semaphore(MASS_SWITCH_CONCURRENCY);
    List<Thread> workers = new ArrayList<>();
    for (TrackedPlayer target : targets) {
      if (dest.equalsIgnoreCase(target.currentBackend())) continue;
      Thread worker = Thread.startVirtualThread(() -> {
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
      });
      workers.add(worker);
    }
    for (Thread worker : workers) {
      try { worker.join(); }
      catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    ConduitUi.brandLine(source);
    ConduitUi.success(source, "Sent " + moved.get() + " players");
    source.sendMessage(Text.of("  ").append(Text.of(fromDisplay).color(ConduitUi.MUTED))
        .append(Text.of(" → ").color(ConduitUi.ACCENT))
        .append(Text.of(destDisplay).color(ConduitUi.TITLE)));
    if (failed.get() > 0) {
      ConduitUi.failure(source, failed.get() == 1 ? "1 player could not be moved." : failed.get() + " players could not be moved.");
    }
  }

  private static Optional<String> resolveServer(CommandSource source, ServerRegistry registry, String query) {
    ServerMatch match = registry.resolve(query);
    if (match.kind() == ServerMatch.Kind.NONE) {
      ConduitUi.failure(source, "Unknown server: " + query);
      return Optional.empty();
    }
    if (match.kind() == ServerMatch.Kind.AMBIGUOUS) {
      ConduitUi.failure(source, "Multiple servers match:");
      for (String name : match.candidates()) {
        source.sendMessage(Text.of("  ● ").color(ConduitUi.MUTED).append(Text.of(name).color(ConduitUi.TITLE)
            .clickRun("/server " + name)));
      }
      return Optional.empty();
    }
    return Optional.of(match.server().orElseThrow().name());
  }

  private static List<String> completeServers(ServerRegistry registry, ConduitRuntime runtime, List<String> arguments) {
    if (arguments.size() > 1) return List.of();
    String prefix = arguments.isEmpty() ? "" : arguments.getFirst();
    List<String> names = new ArrayList<>();
    for (String name : registry.names()) {
      if (!prefix.isEmpty() && !name.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) continue;
      if (runtime != null && runtime.selector().status(name).availability() == ServerAvailability.OFFLINE) {
        names.add(name); // still suggest, status is visible in /server UI
      } else {
        names.add(name);
      }
    }
    return names;
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

  private static List<String> completeConduit(CommandSource source, List<String> arguments) {
    if (arguments.size() > 1) return List.of();
    List<String> all = List.of("info", "plugins", "servers", "uptime", "dump", "heap", "reload", "metrics", "health", "help");
    return prefix(all, arguments.isEmpty() ? "" : arguments.getFirst());
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

  private static String padRight(String value, int width) {
    if (value.length() >= width) return value;
    return value + " ".repeat(width - value.length());
  }
}
