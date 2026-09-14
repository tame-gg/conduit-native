package gg.tame.conduit.command;

import gg.tame.conduit.Conduit;
import gg.tame.conduit.api.server.ServerAvailability;
import gg.tame.conduit.metrics.ConduitMetrics;
import gg.tame.conduit.plugin.PluginCatalog;
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

/** Built-in Conduit commands. Player-facing output stays plain and short. */
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
      source.sendMessage("You are currently connected to: " + current);
      source.sendMessage("");
      source.sendMessage("Servers:");
      for (String name : registry.names()) {
        boolean here = name.equalsIgnoreCase(current);
        source.sendMessage("  " + (here ? "●" : "○") + " " + name);
      }
      return;
    }
    Optional<String> destination = resolveServer(source, registry, arguments.getFirst());
    if (destination.isEmpty()) return;
    String name = destination.get();
    if (name.equalsIgnoreCase(source.currentBackend())) {
      source.sendMessage("You are already connected to " + name + ".");
      return;
    }
    if (!(source instanceof TrackedPlayer player)) {
      source.sendMessage(name + " is unavailable. Please try again later.");
      return;
    }
    if (runtime != null && runtime.selector().status(name).availability() == ServerAvailability.OFFLINE) {
      source.sendMessage(name + " is unavailable. Please try again later.");
      return;
    }
    source.sendMessage("Connecting to " + name + "...");
    if (!player.transferTo(name)) {
      // transferTo also notifies on async paths; keep a sync fallback message.
      if (Thread.currentThread() != null) {
        // Message is emitted by PlayerSession for both success and failure.
      }
    }
  }

  private static void glist(CommandSource source, PlayerManager players, ServerRegistry registry) {
    source.sendMessage("There are " + players.all().size() + " player(s) online.");
    for (String server : registry.names()) {
      List<TrackedPlayer> on = players.byServer(server);
      if (on.isEmpty()) continue;
      source.sendMessage("[" + server + "] (" + on.size() + "): " + joinNames(on));
    }
  }

  private static void plist(CommandSource source, PlayerManager players, ServerRegistry registry, List<String> arguments) {
    if (arguments.isEmpty()) {
      source.sendMessage("Usage: /plist <server>");
      return;
    }
    Optional<String> server = resolveServer(source, registry, arguments.getFirst());
    if (server.isEmpty()) return;
    List<TrackedPlayer> on = players.byServer(server.get());
    source.sendMessage("[" + server.get() + "] (" + on.size() + "): " + (on.isEmpty() ? "(none)" : joinNames(on)));
  }

  private static void find(CommandSource source, PlayerManager players, List<String> arguments) {
    if (arguments.isEmpty()) {
      source.sendMessage("Usage: /find <player>");
      return;
    }
    Optional<TrackedPlayer> found = players.getByUsername(arguments.getFirst());
    if (found.isEmpty()) {
      source.sendMessage("Player " + arguments.getFirst() + " is not online.");
      return;
    }
    TrackedPlayer player = found.get();
    String server = player.currentBackend() == null || player.currentBackend().isBlank() ? "connecting" : player.currentBackend();
    source.sendMessage(player.username() + " is on " + server + ".");
  }

  private static void alert(CommandSource source, PlayerManager players, List<String> arguments) {
    if (arguments.isEmpty()) {
      source.sendMessage("Usage: /alert <message>");
      return;
    }
    String body = "[Alert] " + String.join(" ", arguments);
    int sent = 0;
    for (TrackedPlayer player : players.all()) {
      if (player instanceof CommandSource commandSource) {
        commandSource.sendMessage(body);
        sent++;
      }
    }
    source.sendMessage("Alert sent to " + sent + " player(s).");
  }

  private static void ping(CommandSource source) {
    source.sendMessage("Connected through Conduit " + Conduit.VERSION + ".");
  }

  private static void hub(CommandSource source, ConduitRuntime runtime) {
    if (!(source instanceof TrackedPlayer player) || runtime == null) {
      source.sendMessage("Unable to resolve hub server.");
      return;
    }
    var candidates = runtime.selector().candidates();
    if (candidates.isEmpty()) {
      source.sendMessage("No servers configured.");
      return;
    }
    String hub = candidates.getFirst().name();
    if (hub.equalsIgnoreCase(source.currentBackend())) {
      source.sendMessage("You are already connected to " + hub + ".");
      return;
    }
    source.sendMessage("Connecting to " + hub + "...");
    player.transferTo(hub);
  }

  private static void gkick(CommandSource source, PlayerManager players, List<String> arguments) {
    if (arguments.isEmpty()) {
      source.sendMessage("Usage: /gkick <player> [reason]");
      return;
    }
    Optional<TrackedPlayer> found = players.getByUsername(arguments.getFirst());
    if (found.isEmpty()) {
      source.sendMessage("Player " + arguments.getFirst() + " is not online.");
      return;
    }
    TrackedPlayer target = found.get();
    String reason = arguments.size() == 1 ? "Kicked by an operator." : String.join(" ", arguments.subList(1, arguments.size()));
    if (target instanceof gg.tame.conduit.api.player.Player api) {
      api.disconnect(reason);
      source.sendMessage("Kicked " + target.username() + ".");
      return;
    }
    source.sendMessage("Unable to kick " + target.username() + ".");
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
      case "uptime" -> source.sendMessage("Uptime: " + formatUptime(runtime == null ? 0 : runtime.uptimeMillis()));
      case "dump" -> dump(source, runtime, registry);
      case "heap" -> heap(source);
      case "reload" -> reload(source, runtime);
      case "metrics" -> source.sendMessage(ConduitMetrics.current().snapshot().toString());
      case "health" -> health(source, runtime, registry);
      case "help" -> help(source);
      default -> source.sendMessage("Unknown /conduit subcommand. Try /conduit help");
    }
  }

  private static void info(CommandSource source, ConduitRuntime runtime, ServerRegistry registry) {
    source.sendMessage("Conduit " + Conduit.VERSION);
    source.sendMessage("");
    String current = source.currentBackend().isBlank() ? "none" : source.currentBackend();
    source.sendMessage("Current server: " + current);
    source.sendMessage("Servers: " + registry.names().size());
    if (runtime != null) {
      source.sendMessage("Players: " + ConduitMetrics.current().activePlayers());
      source.sendMessage("Plugins: " + runtime.pluginCatalog().size());
    }
  }

  private static void servers(CommandSource source, ConduitRuntime runtime, ServerRegistry registry) {
    source.sendMessage("Servers:");
    for (String name : registry.names()) {
      String state = "unknown";
      if (runtime != null) {
        var status = runtime.selector().status(name);
        state = status.availability().name().toLowerCase(Locale.ROOT);
      }
      int players = runtime == null ? 0 : runtime.playerManager().byServer(name).size();
      source.sendMessage("  " + name + " — " + state + " (" + players + " on proxy)");
    }
  }

  private static void plugins(CommandSource source, ConduitRuntime runtime) {
    if (!source.hasPermission(Permissions.PLUGINS) && !source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      source.sendMessage("You do not have permission to use this command.");
      return;
    }
    PluginCatalog catalog = runtime == null ? null : runtime.pluginCatalog();
    if (catalog == null || catalog.size() == 0) {
      source.sendMessage("No Conduit or Velocity plugins loaded.");
      return;
    }
    source.sendMessage("Proxy plugins (" + catalog.size() + "):");
    for (PluginCatalog.Entry entry : catalog.all()) source.sendMessage("- " + entry.display());
  }

  private static void help(CommandSource source) {
    source.sendMessage("Conduit commands:");
    if (source.hasPermission(Permissions.SERVER_USE) || source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      source.sendMessage("/server");
      source.sendMessage("/server <server>");
    }
    if (source.hasPermission(Permissions.SERVER_SEND) || source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      source.sendMessage("/send <player|server|current> <server>");
    }
    if (source.hasPermission(Permissions.CONDUIT_INFO) || source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      source.sendMessage("/conduit");
      source.sendMessage("/conduit servers");
      source.sendMessage("/conduit plugins");
    }
    if (source.hasPermission(Permissions.HUB) || source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      source.sendMessage("/hub");
    }
  }

  private static void health(CommandSource source, ConduitRuntime runtime, ServerRegistry registry) {
    if (runtime == null) {
      source.sendMessage("Runtime unavailable.");
      return;
    }
    source.sendMessage("Backend health:");
    for (String name : registry.names()) {
      var status = runtime.selector().status(name);
      source.sendMessage("- " + name + ": " + status.availability().name().toLowerCase(Locale.ROOT));
    }
  }

  private static void reload(CommandSource source, ConduitRuntime runtime) {
    if (!source.hasPermission(Permissions.RELOAD) && !source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      source.sendMessage("You do not have permission to use this command.");
      return;
    }
    if (runtime == null) {
      source.sendMessage("Unable to reload.");
      return;
    }
    runtime.selector().probeAll();
    source.sendMessage("Re-probed configured backends.");
  }

  private static void dump(CommandSource source, ConduitRuntime runtime, ServerRegistry registry) {
    if (!source.hasPermission(Permissions.DUMP) && !source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      source.sendMessage("You do not have permission to use this command.");
      return;
    }
    try {
      Path dir = Path.of("dumps");
      Files.createDirectories(dir);
      Path file = dir.resolve("conduit-" + Instant.now().toString().replace(':', '-') + ".txt");
      StringBuilder body = new StringBuilder();
      body.append("Conduit ").append(Conduit.VERSION).append('\n');
      body.append("uptimeMs=").append(runtime == null ? 0 : runtime.uptimeMillis()).append('\n');
      body.append("metrics=").append(ConduitMetrics.current().snapshot()).append('\n');
      for (String name : registry.names()) body.append("server=").append(name).append('\n');
      Files.writeString(file, body.toString());
      source.sendMessage("Wrote dump to " + file.toAbsolutePath());
    } catch (IOException exception) {
      source.sendMessage("Dump failed.");
    }
  }

  private static void heap(CommandSource source) {
    if (!source.hasPermission(Permissions.HEAP) && !source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      source.sendMessage("You do not have permission to use this command.");
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
      source.sendMessage("Heap dump written to " + file.toAbsolutePath());
    } catch (ReflectiveOperationException | IOException exception) {
      source.sendMessage("Heap dump unavailable.");
    }
  }

  private static void send(CommandSource source, ServerRegistry registry, PlayerManager players, ConduitRuntime runtime, List<String> arguments) {
    if (arguments.size() != 2) {
      source.sendMessage("Usage: /send <source-server> <destination-server>");
      source.sendMessage("       /send current <destination-server>");
      source.sendMessage("       /send <player> <destination-server>");
      return;
    }
    Optional<String> destination = resolveServer(source, registry, arguments.get(1));
    if (destination.isEmpty()) return;
    String dest = destination.get();
    if (runtime != null && runtime.selector().status(dest).availability() == ServerAvailability.OFFLINE) {
      source.sendMessage(dest + " is unavailable. Please try again later.");
      return;
    }
    String from = arguments.getFirst();
    if (from.equalsIgnoreCase("current")) {
      if (!(source instanceof TrackedPlayer player)) {
        source.sendMessage("current can only be used by a player.");
        return;
      }
      if (dest.equalsIgnoreCase(source.currentBackend())) {
        source.sendMessage(player.username() + " is already connected to " + dest + ".");
        return;
      }
      source.sendMessage("Connecting to " + dest + "...");
      if (player.transferTo(dest)) source.sendMessage("Sent " + player.username() + " to " + dest + ".");
      return;
    }
    Optional<TrackedPlayer> player = players.getByUsername(from);
    if (player.isPresent()) {
      if (!source.hasPermission(Permissions.SERVER_SEND_OTHERS) && !source.hasPermission(Permissions.CONDUIT_ADMIN)) {
        source.sendMessage("You do not have permission to use this command.");
        return;
      }
      TrackedPlayer target = player.get();
      if (dest.equalsIgnoreCase(target.currentBackend())) {
        source.sendMessage(target.username() + " is already connected to " + dest + ".");
        return;
      }
      if (!target.transferTo(dest)) {
        source.sendMessage(dest + " is unavailable. Please try again later.");
        return;
      }
      source.sendMessage("Sent " + target.username() + " to " + dest + ".");
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
    source.sendMessage("Player " + from + " is not online.");
  }

  private static void sendMass(CommandSource source, PlayerManager players, String from, String dest) {
    if (!source.hasPermission(Permissions.SERVER_SEND_MASS) && !source.hasPermission(Permissions.CONDUIT_ADMIN)) {
      source.sendMessage("You do not have permission to use this command.");
      return;
    }
    List<TrackedPlayer> targets = players.byServer(from);
    if (targets.isEmpty()) {
      source.sendMessage("No players are connected to " + from + ".");
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
    source.sendMessage("Sent " + moved.get() + " players from " + from + " to " + dest + ".");
    if (failed.get() > 0) source.sendMessage(failed.get() + " player(s) could not be moved.");
  }

  private static Optional<String> resolveServer(CommandSource source, ServerRegistry registry, String query) {
    ServerMatch match = registry.resolve(query);
    if (match.kind() == ServerMatch.Kind.NONE) {
      source.sendMessage("Unknown server: " + query);
      return Optional.empty();
    }
    if (match.kind() == ServerMatch.Kind.AMBIGUOUS) {
      source.sendMessage("Multiple servers match:");
      for (String name : match.candidates()) source.sendMessage(name);
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
    return prefix(List.of("info", "plugins", "servers", "uptime", "dump", "heap", "reload", "metrics", "health", "help"),
        arguments.isEmpty() ? "" : arguments.getFirst());
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
