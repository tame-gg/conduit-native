package gg.tame.conduit.command;

import gg.tame.conduit.Conduit;
import gg.tame.conduit.routing.ServerMatch;
import gg.tame.conduit.routing.ServerRegistry;
import gg.tame.conduit.session.PlayerManager;
import gg.tame.conduit.session.TrackedPlayer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

public final class CoreCommands {
  private static final int MASS_SWITCH_CONCURRENCY = 16;
  private CoreCommands() {}
  public static void register(CommandManager manager, ServerRegistry registry, PlayerManager players) {
    manager.register(new RegisteredCommand("server", List.of(), Permissions.SERVER_USE,
        (source, arguments) -> server(source, registry, arguments),
        (source, arguments) -> completeServers(registry, arguments)));
    manager.register(new RegisteredCommand("conduit", List.of(), Permissions.CONDUIT_INFO,
        (source, arguments) -> conduit(source), (source, arguments) -> List.of()));
    manager.register(new RegisteredCommand("send", List.of(), Permissions.SERVER_SEND,
        (source, arguments) -> send(source, registry, players, arguments),
        (source, arguments) -> completeSend(source, registry, players, arguments)));
  }
  private static void server(CommandSource source, ServerRegistry registry, List<String> arguments) {
    if (arguments.isEmpty()) {
      String current = source.currentBackend().isBlank() ? "none" : source.currentBackend();
      source.sendMessage("Current server: " + current);
      source.sendMessage("");
      source.sendMessage("Available servers:");
      for (String name : registry.names()) source.sendMessage("- " + name);
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
      source.sendMessage("Unable to connect to " + name + ".");
      return;
    }
    if (!player.transferTo(name)) source.sendMessage("Unable to connect to " + name + ".");
  }
  private static void conduit(CommandSource source) {
    source.sendMessage("Conduit");
    source.sendMessage("Version: " + Conduit.VERSION);
    source.sendMessage("API: " + Conduit.API_VERSION);
    source.sendMessage("Current server: " + (source.currentBackend().isBlank() ? "none" : source.currentBackend()));
    source.sendMessage("Players: " + gg.tame.conduit.metrics.ConduitMetrics.current().activePlayers());
    source.sendMessage(gg.tame.conduit.metrics.ConduitMetrics.current().snapshot().toString());
  }
  private static void send(CommandSource source, ServerRegistry registry, PlayerManager players, List<String> arguments) {
    if (arguments.size() != 2) {
      source.sendMessage("Usage: /send <source-server> <destination-server>");
      source.sendMessage("       /send current <destination-server>");
      source.sendMessage("       /send <player> <destination-server>");
      return;
    }
    String first = arguments.getFirst();
    Optional<String> destination = resolveServer(source, registry, arguments.get(1));
    if (destination.isEmpty()) return;
    String dest = destination.get();
    if (first.equalsIgnoreCase("current")) {
      sendCurrent(source, dest);
      return;
    }
    if (registry.contains(first) || registry.resolve(first).kind() != ServerMatch.Kind.NONE || looksLikeServerName(registry, first)) {
      Optional<String> sourceServer = resolveServer(source, registry, first);
      if (sourceServer.isEmpty()) return;
      sendMass(source, players, sourceServer.get(), dest);
      return;
    }
    sendPlayer(source, players, first, dest);
  }
  private static boolean looksLikeServerName(ServerRegistry registry, String query) {
    String needle = ServerRegistry.normalize(query);
    for (String name : registry.names()) {
      String server = ServerRegistry.normalize(name);
      if (needle.startsWith(server) || server.startsWith(needle)) return true;
    }
    return false;
  }
  private static void sendCurrent(CommandSource source, String dest) {
    if (!(source instanceof TrackedPlayer player)) {
      source.sendMessage("current can only be used by a player.");
      return;
    }
    if (dest.equalsIgnoreCase(player.currentBackend())) {
      source.sendMessage(player.username() + " is already connected to " + dest + ".");
      return;
    }
    if (!player.transferTo(dest)) source.sendMessage("Unable to send " + player.username() + " to " + dest + ".");
  }
  private static void sendPlayer(CommandSource source, PlayerManager players, String username, String dest) {
    if (!source.hasPermission(Permissions.SERVER_SEND_OTHERS)) {
      source.sendMessage("You do not have permission to do that.");
      return;
    }
    Optional<TrackedPlayer> found = players.getByUsername(username);
    if (found.isEmpty()) {
      source.sendMessage("Player " + username + " is not online.");
      return;
    }
    TrackedPlayer target = found.get();
    if (dest.equalsIgnoreCase(target.currentBackend())) {
      source.sendMessage(target.username() + " is already connected to " + dest + ".");
      return;
    }
    if (!target.transferTo(dest)) source.sendMessage("Unable to send " + target.username() + " to " + dest + ".");
  }
  private static void sendMass(CommandSource source, PlayerManager players, String from, String dest) {
    if (!source.hasPermission(Permissions.SERVER_SEND_MASS)) {
      source.sendMessage("You do not have permission to do that.");
      return;
    }
    List<TrackedPlayer> group = players.byServer(from);
    if (group.isEmpty()) {
      source.sendMessage("No players are connected to " + from + ".");
      return;
    }
    source.sendMessage("Sending players from " + from + " to " + dest + "...");
    AtomicInteger moved = new AtomicInteger();
    AtomicInteger failed = new AtomicInteger();
    Semaphore permits = new Semaphore(MASS_SWITCH_CONCURRENCY);
    List<Thread> workers = new ArrayList<>();
    for (TrackedPlayer player : group) {
      if (dest.equalsIgnoreCase(player.currentBackend())) continue;
      Thread worker = Thread.startVirtualThread(() -> {
        try {
          permits.acquire();
          try {
            if (player.transferTo(dest)) moved.incrementAndGet();
            else failed.incrementAndGet();
          } finally { permits.release(); }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          failed.incrementAndGet();
        }
      });
      workers.add(worker);
    }
    for (Thread worker : workers) {
      try { worker.join(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    source.sendMessage("Sent " + moved.get() + " players to " + dest + ".");
    if (failed.get() > 0) {
      source.sendMessage(failed.get() == 1 ? "1 player could not be moved." : failed.get() + " players could not be moved.");
    }
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
  private static List<String> prefix(List<String> values, String prefix) {
    String needle = prefix.toLowerCase(Locale.ROOT);
    List<String> names = new ArrayList<>();
    for (String value : values) {
      if (needle.isEmpty() || value.toLowerCase(Locale.ROOT).startsWith(needle)) names.add(value);
    }
    return names;
  }
}
