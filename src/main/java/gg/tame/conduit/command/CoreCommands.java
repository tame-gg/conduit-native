// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.command;

import gg.tame.conduit.Conduit;
import gg.tame.conduit.api.server.ServerAvailability;
import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.metrics.ConduitMetrics;
import gg.tame.conduit.ops.BanList;
import gg.tame.conduit.ops.Whitelist;
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
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Built-in Conduit commands. Clean, polished proxy UX — not a dashboard. */
public final class CoreCommands {
  private static final int MASS_SWITCH_CONCURRENCY = 16;
  /** Every /conduit subcommand, in help order. Drives both tab completion and the client graph. */
  public static final List<String> CONDUIT_SUBCOMMANDS = List.of("info", "plugins", "servers", "uptime",
      "dump", "heap", "reload", "metrics", "health", "maintenance", "drain", "undrain", "doctor",
      "diagnostics", "attack", "alert", "cache", "help");
  /**
   * The node each subcommand needs. One each, so that doctor never brings reload with it; drain and
   * undrain share one, being one switch. help has none: it lists only what the source may run.
   * shutdown has none either, being the console's alone.
   */
  private static final java.util.Map<String, String> SUBCOMMAND_NODES = java.util.Map.ofEntries(
      java.util.Map.entry("info", Permissions.INFO), java.util.Map.entry("plugins", Permissions.PLUGINS),
      java.util.Map.entry("servers", Permissions.SERVERS), java.util.Map.entry("uptime", Permissions.UPTIME),
      java.util.Map.entry("dump", Permissions.DUMP), java.util.Map.entry("heap", Permissions.HEAP),
      java.util.Map.entry("reload", Permissions.RELOAD), java.util.Map.entry("metrics", Permissions.METRICS),
      java.util.Map.entry("health", Permissions.HEALTH), java.util.Map.entry("maintenance", Permissions.MAINTENANCE),
      java.util.Map.entry("drain", Permissions.DRAIN), java.util.Map.entry("undrain", Permissions.DRAIN),
      java.util.Map.entry("doctor", Permissions.DOCTOR), java.util.Map.entry("diagnostics", Permissions.DIAGNOSTICS),
      java.util.Map.entry("attack", Permissions.ATTACK), java.util.Map.entry("alert", Permissions.ALERT),
      java.util.Map.entry("cache", Permissions.CACHE));
  private static final java.util.Map<String, String> ALIASES = java.util.Map.of("version", "info", "attackmode", "attack");
  private static final Set<String> RESERVED = Set.of(
      "server", "send", "glist", "plist", "find", "alert", "ping", "hub", "gkick", "conduit",
      "gban", "gunban", "gpardon", "gbanlist", "galts", "gmute", "gunmute", "gwarn", "gwhitelist", "gwl");
  private CoreCommands() {}

  public static void register(CommandManager manager, ServerRegistry registry, PlayerManager players) {
    register(manager, registry, players, null);
  }

  public static void register(ConduitRuntime runtime) {
    register(runtime.commandManager(), runtime.selector().registry(), runtime.playerManager(), runtime);
  }

  public static void register(CommandManager manager, ServerRegistry registry, PlayerManager players, ConduitRuntime runtime) {
    // /server, the /<server> shortcuts, /hub and /ping carry no permission: every player may use them.
    manager.register(new RegisteredCommand("server", List.of(), null,
        (source, arguments) -> server(source, registry, runtime, arguments),
        (source, arguments) -> completeServers(registry, arguments)));
    manager.register(new RegisteredCommand("send", List.of(), Permissions.SEND,
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
    manager.register(new RegisteredCommand("ping", List.of(), null,
        (source, arguments) -> ping(source), (source, arguments) -> List.of()));
    manager.register(new RegisteredCommand("hub", List.of(), null,
        (source, arguments) -> hub(source, runtime), (source, arguments) -> List.of()));
    manager.register(new RegisteredCommand("gkick", List.of(), Permissions.GKICK,
        (source, arguments) -> gkick(source, players, arguments),
        (source, arguments) -> completePlayers(players, arguments)));
    manager.register(new RegisteredCommand("gban", List.of(), Permissions.GBAN,
        (source, arguments) -> gban(source, runtime, players, arguments),
        (source, arguments) -> completeGban(players, arguments)));
    manager.register(new RegisteredCommand("gmute", List.of(), Permissions.GMUTE,
        (source, arguments) -> gmute(source, runtime, players, arguments),
        (source, arguments) -> completePlayers(players, arguments)));
    manager.register(new RegisteredCommand("gunmute", List.of(), Permissions.GMUTE,
        (source, arguments) -> gunmute(source, runtime, players, arguments),
        (source, arguments) -> completePlayers(players, arguments)));
    manager.register(new RegisteredCommand("gwarn", List.of(), Permissions.GWARN,
        (source, arguments) -> gwarn(source, players, arguments),
        (source, arguments) -> completePlayers(players, arguments)));
    manager.register(new RegisteredCommand("galts", List.of(), Permissions.GALTS,
        (source, arguments) -> galts(source, runtime, arguments),
        (source, arguments) -> completePlayers(players, arguments)));
    manager.register(new RegisteredCommand("gunban", List.of("gpardon"), Permissions.GBAN,
        (source, arguments) -> gunban(source, runtime, players, arguments),
        (source, arguments) -> completeBanned(runtime, arguments)));
    manager.register(new RegisteredCommand("gbanlist", List.of(), Permissions.GBAN,
        (source, arguments) -> gbanlist(source, runtime, arguments),
        (source, arguments) -> List.of()));
    manager.register(new RegisteredCommand("gwhitelist", List.of("gwl"), Permissions.GWHITELIST,
        (source, arguments) -> gwhitelist(source, runtime, players, arguments),
        (source, arguments) -> completeWhitelist(runtime, players, arguments)));
    // No node of its own: each subcommand checks its own. For a player who may run none of them,
    // /conduit is not there at all, and their line goes on to the backend.
    manager.register(new RegisteredCommand("conduit", List.of(), null,
        (source, arguments) -> conduit(source, runtime, registry, arguments),
        (source, arguments) -> completeConduit(source, registry, arguments),
        (source, arguments) -> !conduitSubcommands(source).isEmpty()));
    // What a hosting panel types into the console to stop or restart a proxy: "stop", or the "end"
    // BungeeCord answers to. The console's alone, and by requirement rather than by refusal, so a
    // player's /stop is not there -- not in their command tree, and passed on to the backend that owns it.
    manager.register(new RegisteredCommand("stop", List.of("end"), null,
        (source, arguments) -> shutdown(source, runtime, arguments), (source, arguments) -> List.of(),
        (source, arguments) -> source instanceof ConsoleCommandSource));
    for (String name : registry.names()) {
      String key = name.toLowerCase(Locale.ROOT);
      if (RESERVED.contains(key)) continue;
      String target = name;
      try {
        manager.register(new RegisteredCommand(key, List.of(), null,
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
      if (runtime.health().isDraining(name) && !Permissions.allows(source, Permissions.DRAIN_BYPASS)) {
        Messages.failure(source, name + " is draining; try again later.");
        return;
      }
      var backend = registry.get(name);
      if (backend.isPresent() && backend.get().full(runtime.playerManager().byServer(name).size())) {
        Messages.failure(source, name + " is full (" + backend.get().maxPlayers() + " players).");
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
    long latency = source instanceof gg.tame.conduit.api.player.Player player ? player.ping() : -1;
    source.sendMessage(Text.of("Connected through ").color(Messages.LABEL)
        .append(Text.of(Conduit.BRAND + " " + Conduit.VERSION).color(Messages.BRAND).bold())
        .append(Text.of(latency < 0 ? "." : ", ping " + latency + " ms.").color(Messages.LABEL)));
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
    // The first one health checks and drains allow; the first configured one only when none is.
    String hub = candidates.stream().filter(server -> runtime.health().isRoutable(server.name())).findFirst()
        .orElse(candidates.getFirst()).name();
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
    if (target instanceof gg.tame.conduit.api.player.Player api && protectedFrom(source, api, Permissions.GKICK)) {
      Messages.failure(source, target.username() + " cannot be kicked by another player.");
      return;
    }
    if (target instanceof gg.tame.conduit.api.player.Player api) {
      api.disconnect(reason);
      Messages.success(source, "Kicked " + target.username() + ".");
      notifyStaff(source, players, "kicked " + target.username() + ": " + reason);
      return;
    }
    Messages.failure(source, "Unable to kick " + target.username() + ".");
  }

  /**
   * {@code /gban <player|address> [duration] [reason]}. No duration means permanent, which is what
   * an operator typing a name and a reason means. A player who is online is kicked with the same
   * message the ban will show them from now on, and their account is banned alongside their name so
   * that changing it does not get them back in.
   */
  private static void gban(CommandSource source, ConduitRuntime runtime, PlayerManager players, List<String> arguments) {
    if (runtime == null) { Messages.failure(source, "Runtime unavailable."); return; }
    if (arguments.isEmpty()) {
      Messages.info(source, "Usage: /gban <player|address|range> [duration] [reason]");
      Messages.info(source, "Duration is 30m, 2h, 7d, 4w or perm; leaving it out is permanent. A range is CIDR: 203.0.113.0/24.");
      return;
    }
    String target = arguments.getFirst();
    // Staff cannot ban themselves, by name or by an address that covers their own.
    if (source instanceof gg.tame.conduit.api.player.Player self && (target.equalsIgnoreCase(self.username())
        || BanList.looksLikeAddress(target) && BanList.validAddress(target)
            && BanList.covers(BanList.normalise(BanList.Kind.ADDRESS, target), self.remoteAddress().getHostAddress()))) {
      Messages.failure(source, "You cannot ban yourself.");
      return;
    }
    List<String> rest = arguments.subList(1, arguments.size());
    // The second word is a length only if it reads as one; otherwise it is the start of the reason.
    long expiresAt = BanList.PERMANENT;
    if (!rest.isEmpty()) {
      Optional<Long> parsed = BanList.parseExpiry(rest.getFirst());
      if (parsed.isPresent()) { expiresAt = parsed.get(); rest = rest.subList(1, rest.size()); }
    }
    gg.tame.conduit.config.BanSettings screen = runtime.configuration().ops().bans();
    String reason = screen.reasonOr(String.join(" ", rest));
    String actor = source.username();
    BanList bans = runtime.bans();

    // A second /gban used to replace the first and report "Banned" as though it were new. Someone
    // already on the list is said to be, with the ban that stands; changing it is an unban first.
    boolean address = BanList.looksLikeAddress(target);
    Optional<BanList.Entry> existing = address ? bans.find(null, null, BanList.normalise(BanList.Kind.ADDRESS, target))
        : bans.find(target, null, null);
    if (existing.isPresent()) {
      BanList.Entry standing = existing.get();
      Messages.failure(source, target + " is already banned " + describeBan(standing) + " by " + standing.actor()
          + ": " + standing.reason() + ". Use /gunban " + target + " first to change it.");
      return;
    }

    if (address) {
      if (!BanList.validAddress(target)) {
        Messages.failure(source, "Not an IP address or CIDR range: " + target);
        return;
      }
      String banned = BanList.normalise(BanList.Kind.ADDRESS, target);
      // Refused whole rather than banned around: the address ban would keep them out at their next login anyway.
      for (TrackedPlayer tracked : players.all()) {
        if (tracked instanceof gg.tame.conduit.api.player.Player api && BanList.covers(banned, api.remoteAddress().getHostAddress())
            && protectedFrom(source, api, Permissions.GBAN)) {
          Messages.failure(source, api.username() + " is on that address and cannot be banned by another player.");
          return;
        }
      }
      BanList.Entry entry = bans.ban(BanList.Kind.ADDRESS, target, reason, actor, expiresAt);
      int kicked = kickMatching(players, player -> BanList.covers(entry.value(), player.remoteAddress().getHostAddress()),
          screen.render(reason, actor, entry.remaining(System.currentTimeMillis())));
      Messages.success(source, "Banned address " + entry.value() + " " + describeBan(entry)
          + (kicked > 0 ? " (" + kicked + (kicked == 1 ? " player" : " players") + " kicked)" : "") + ".");
      notifyStaff(source, players, "banned address " + entry.value() + " " + describeBan(entry) + ": " + reason);
      return;
    }

    Optional<TrackedPlayer> onlineTarget = players.getByUsername(target);
    if (onlineTarget.isPresent() && onlineTarget.get() instanceof gg.tame.conduit.api.player.Player api && protectedFrom(source, api, Permissions.GBAN)) {
      Messages.failure(source, api.username() + " cannot be banned by another player.");
      return;
    }
    if (onlineTarget.isEmpty() && protectedOffline(source, runtime, target, null)) {
      Messages.failure(source, target + " cannot be banned by another player.");
      return;
    }
    // An online player's account is banned alongside their name, so a name change does not undo
    // it. An offline one's is asked of Mojang: a name no account has is refused, and a real one's
    // account is banned too. Only where Conduit authenticates with Mojang, since an offline-mode
    // player's UUID is not Mojang's and a Mojang UUID would match nobody there.
    if (onlineTarget.isPresent()) {
      banName(source, players, bans, screen, target, reason, actor, expiresAt, Optional.of(onlineTarget.get().uniqueId()), null);
      return;
    }
    if (runtime.configuration().authentication().mode() != gg.tame.conduit.config.AuthenticationMode.ONLINE) {
      banName(source, players, bans, screen, target, reason, actor, expiresAt, Optional.empty(), null);
      return;
    }
    if (!gg.tame.conduit.auth.MojangProfiles.validName(target)) {
      Messages.failure(source, target + " is not a valid Minecraft username.");
      return;
    }
    Messages.info(source, "Looking up " + target + " at Mojang...");
    final long until = expiresAt;
    // Off the command's thread: the lookup can take seconds, and nothing else waits on it.
    gg.tame.conduit.network.SocketThreads.start(() -> {
      var result = gg.tame.conduit.auth.MojangProfiles.lookup(target);
      switch (result) {
        case gg.tame.conduit.auth.MojangProfiles.Result.Found found -> {
          // Asked again by account: a staff member who changed their name is still the same one.
          if (protectedOffline(source, runtime, found.name(), found.account())) {
            Messages.failure(source, found.name() + " cannot be banned by another player.");
          } else {
            banName(source, players, bans, screen, found.name(), reason, actor, until, Optional.of(found.account()), null);
          }
        }
        case gg.tame.conduit.auth.MojangProfiles.Result.NotFound notFound ->
            Messages.failure(source, target + " is not a Minecraft account, so nobody was banned.");
        case gg.tame.conduit.auth.MojangProfiles.Result.Unavailable unavailable ->
            banName(source, players, bans, screen, target, reason, actor, until, Optional.empty(),
                "Mojang could not be asked (" + unavailable.why() + "), so only the name is banned");
      }
    });
  }

  /**
   * Bans a name, and the account behind it when that is known, and says so. {@code caveat} is added to
   * the confirmation when the account could not be banned for a reason the staff member should know.
   */
  private static void banName(CommandSource source, PlayerManager players, BanList bans, gg.tame.conduit.config.BanSettings screen,
      String target, String reason, String actor, long expiresAt, Optional<java.util.UUID> account, String caveat) {
    // Asked again: a lookup takes time, and someone else may have banned them meanwhile.
    if (bans.find(target, account.orElse(null), null).isPresent()) {
      Messages.failure(source, target + " is already banned. Use /gunban " + target + " first to change it.");
      return;
    }
    BanList.Entry entry = bans.ban(BanList.Kind.NAME, target, reason, actor, expiresAt);
    account.ifPresent(uuid -> bans.ban(BanList.Kind.ACCOUNT, uuid.toString(), reason, actor, entry.expiresAt(), target));
    int kicked = kickMatching(players, player -> player.username().equalsIgnoreCase(target)
            || account.isPresent() && player.uniqueId().equals(account.get()),
        screen.render(reason, actor, entry.remaining(System.currentTimeMillis())));
    Messages.success(source, "Banned " + target + " " + describeBan(entry)
        + (account.isPresent() ? ", account " + account.get() : "")
        + (kicked > 0 ? ", and kicked them" : " (they are not online)") + "."
        + (caveat == null ? "" : " " + caveat + "."));
    notifyStaff(source, players, "banned " + target + " " + describeBan(entry) + ": " + reason);
  }

  /**
   * Whether {@code target} is out of {@code source}'s reach: a player may not kick or ban another who
   * holds that same power, or {@link Permissions#PUNISH_EXEMPT}, so staff cannot turn it on each
   * other. The console is never stopped, and nor is a player acting on themselves. Only an online
   * player can be asked this; someone offline is {@link #protectedOffline} instead.
   */
  private static boolean protectedFrom(CommandSource source, gg.tame.conduit.api.player.Player target, String power) {
    if (!(source instanceof gg.tame.conduit.api.player.Player actor)) return false;
    if (actor.uniqueId().equals(target.uniqueId())) return false;
    return Permissions.allows(target, power) || Permissions.allows(target, Permissions.PUNISH_EXEMPT);
  }

  /**
   * The same question about someone offline, whom the provider cannot be asked about: what they held
   * when last seen, or the {@code [permissions]} operators, which hold every node without logging in.
   */
  private static boolean protectedOffline(CommandSource source, ConduitRuntime runtime, String username, java.util.UUID account) {
    if (!(source instanceof gg.tame.conduit.api.player.Player actor)) return false;
    if (actor.username().equalsIgnoreCase(username) || actor.uniqueId().equals(account)) return false;
    return runtime.protectedPlayers().contains(username, account)
        || runtime.configuration().ops().permissions().isOperator(username, account);
  }

  /**
   * {@code /galts <player>}: the other accounts seen from any address this player has used. Names
   * are as last seen, so a renamed alt shows its newest name; the account is what ties them.
   */
  private static void galts(CommandSource source, ConduitRuntime runtime, List<String> arguments) {
    if (runtime == null) { Messages.failure(source, "Runtime unavailable."); return; }
    if (arguments.isEmpty()) { Messages.info(source, "Usage: /galts <player>"); return; }
    String target = arguments.getFirst();
    var history = runtime.addresses();
    var addresses = history.addressesOf(target, null);
    if (addresses.isEmpty()) { Messages.failure(source, "No login from " + target + " is on record."); return; }
    var alts = history.alts(target);
    if (alts.isEmpty()) {
      Messages.info(source, target + " has used " + addresses.size() + (addresses.size() == 1 ? " address" : " addresses")
          + " and no other account has been seen from any of them.");
      return;
    }
    Messages.info(source, alts.size() + (alts.size() == 1 ? " account has" : " accounts have") + " shared an address with " + target + ":");
    long now = System.currentTimeMillis();
    for (var alt : alts) {
      boolean banned = runtime.bans().find(alt.username(), alt.account(), null).isPresent();
      Messages.info(source, "  " + alt.username() + " (" + alt.address() + ", last seen "
          + BanList.describeDuration(Math.max(0, now - alt.lastSeen())) + " ago)" + (banned ? " [banned]" : ""));
    }
  }

  /** {@code /gmute <player> [duration] [reason]}: the player stays, and their chat stops reaching a backend. */
  private static void gmute(CommandSource source, ConduitRuntime runtime, PlayerManager players, List<String> arguments) {
    if (runtime == null) { Messages.failure(source, "Runtime unavailable."); return; }
    if (arguments.isEmpty()) {
      Messages.info(source, "Usage: /gmute <player> [duration] [reason]");
      Messages.info(source, "Duration is 30m, 2h, 7d, 4w or perm; leaving it out is permanent.");
      return;
    }
    Optional<TrackedPlayer> found = players.getByUsername(arguments.getFirst());
    if (found.isEmpty()) { Messages.failure(source, "Player not found: " + arguments.getFirst() + " (only an online player can be muted)."); return; }
    TrackedPlayer target = found.get();
    if (target instanceof gg.tame.conduit.api.player.Player api && protectedFrom(source, api, Permissions.GMUTE)) {
      Messages.failure(source, target.username() + " cannot be muted by another player.");
      return;
    }
    List<String> rest = arguments.subList(1, arguments.size());
    long expiresAt = BanList.PERMANENT;
    if (!rest.isEmpty()) {
      Optional<Long> parsed = BanList.parseExpiry(rest.getFirst());
      if (parsed.isPresent()) { expiresAt = parsed.get(); rest = rest.subList(1, rest.size()); }
    }
    String reason = String.join(" ", rest).isBlank() ? "Muted." : String.join(" ", rest);
    var entry = runtime.mutes().mute(target.uniqueId(), target.username(), reason, source.username(), expiresAt);
    String length = entry.remaining(System.currentTimeMillis()).map(left -> "for " + left).orElse("permanently");
    if (target instanceof gg.tame.conduit.api.player.Player api) {
      api.sendMessage(Text.of("You have been muted " + length + ": " + reason).color(Messages.WARN));
    }
    Messages.success(source, "Muted " + target.username() + " " + length + ".");
    notifyStaff(source, players, "muted " + target.username() + " " + length + ": " + reason);
  }

  /** {@code /gunmute <player>}. */
  private static void gunmute(CommandSource source, ConduitRuntime runtime, PlayerManager players, List<String> arguments) {
    if (runtime == null) { Messages.failure(source, "Runtime unavailable."); return; }
    if (arguments.isEmpty()) { Messages.info(source, "Usage: /gunmute <player>"); return; }
    String target = arguments.getFirst();
    if (runtime.mutes().unmute(target)) {
      players.getByUsername(target).filter(gg.tame.conduit.api.player.Player.class::isInstance)
          .map(gg.tame.conduit.api.player.Player.class::cast)
          .ifPresent(api -> api.sendMessage(Text.of("You are no longer muted.").color(Messages.LABEL)));
      Messages.success(source, "Unmuted " + target + ".");
      notifyStaff(source, players, "unmuted " + target);
    } else {
      Messages.failure(source, target + " is not muted.");
    }
  }

  /** {@code /gwarn <player> <reason>}: the player is told, staff are told, nothing else changes. */
  private static void gwarn(CommandSource source, PlayerManager players, List<String> arguments) {
    if (arguments.size() < 2) { Messages.info(source, "Usage: /gwarn <player> <reason>"); return; }
    Optional<TrackedPlayer> found = players.getByUsername(arguments.getFirst());
    if (found.isEmpty() || !(found.get() instanceof gg.tame.conduit.api.player.Player api)) {
      Messages.failure(source, "Player not found: " + arguments.getFirst());
      return;
    }
    String reason = String.join(" ", arguments.subList(1, arguments.size()));
    api.sendMessage(Text.of("[Warning] ").color(Messages.WARN).bold().append(Text.of(reason).color(Messages.WARN)));
    Messages.success(source, "Warned " + api.username() + ".");
    notifyStaff(source, players, "warned " + api.username() + ": " + reason);
  }

  /** {@code /gunban <player|address>}, which lifts a ban of any kind held against that word. */
  private static void gunban(CommandSource source, ConduitRuntime runtime, PlayerManager players, List<String> arguments) {
    if (runtime == null) { Messages.failure(source, "Runtime unavailable."); return; }
    if (arguments.isEmpty()) {
      Messages.info(source, "Usage: /gunban <player|address>");
      return;
    }
    String target = arguments.getFirst();
    if (runtime.bans().pardonAny(target)) {
      Messages.success(source, "Unbanned " + target + ".");
      notifyStaff(source, players, "unbanned " + target);
    } else {
      Messages.failure(source, target + " is not banned.");
    }
  }

  /**
   * Tells the staff online, and the console, that {@code source} kicked, banned or unbanned someone:
   * {@code [Staff] Kyle banned Griefer permanently: griefing spawn}. Staff are the players who may
   * kick or ban, and anyone given {@link Permissions#NOTIFY_MODERATION} to watch without acting. The
   * one who did it already has their confirmation, so is not told twice; the console is told unless
   * it did it, and so the line is in the log either way.
   */
  private static void notifyStaff(CommandSource source, PlayerManager players, String what) {
    String line = source.username() + " " + what;
    Text alert = Text.of("[Staff] ").color(Messages.WARN).append(Text.of(line).color(Messages.LABEL));
    for (TrackedPlayer tracked : players.all()) {
      if (!(tracked instanceof gg.tame.conduit.api.player.Player staff)) continue;
      if (source instanceof gg.tame.conduit.api.player.Player actor && actor.uniqueId().equals(staff.uniqueId())) continue;
      if (Permissions.allows(staff, Permissions.NOTIFY_MODERATION) || Permissions.allows(staff, Permissions.GKICK)
          || Permissions.allows(staff, Permissions.GBAN)) {
        staff.sendMessage(alert);
      }
    }
    if (!(source instanceof ConsoleCommandSource)) gg.tame.conduit.log.ConduitLog.info("[Staff] " + line);
  }

  /** How many bans one page of {@code /gbanlist} shows, so a long list does not scroll out of chat. */
  private static final int BANS_PER_PAGE = 8;

  /**
   * {@code /gbanlist [page]}: every ban in force, newest first, with its reason, who made it and how
   * long it has left. An account ban made alongside a name ban is that name's, and is not listed twice.
   */
  private static void gbanlist(CommandSource source, ConduitRuntime runtime, List<String> arguments) {
    if (runtime == null) { Messages.failure(source, "Runtime unavailable."); return; }
    List<BanList.Entry> shown = new ArrayList<>();
    for (BanList.Entry entry : runtime.bans().active()) {
      if (entry.kind() == BanList.Kind.ACCOUNT && !entry.alias().isEmpty()) continue;
      shown.add(entry);
    }
    if (shown.isEmpty()) {
      Messages.info(source, "Nobody is banned.");
      return;
    }
    int pages = (shown.size() + BANS_PER_PAGE - 1) / BANS_PER_PAGE;
    int page = 1;
    if (!arguments.isEmpty()) {
      try { page = Integer.parseInt(arguments.getFirst()); }
      catch (NumberFormatException notANumber) { Messages.failure(source, "Usage: /gbanlist [page]"); return; }
    }
    if (page < 1 || page > pages) {
      Messages.failure(source, "There " + (pages == 1 ? "is 1 page" : "are " + pages + " pages") + " of bans.");
      return;
    }
    long now = System.currentTimeMillis();
    source.sendMessage(Text.of("Bans (" + shown.size() + ")").color(Messages.BRAND).bold()
        .append(Text.of("  page " + page + "/" + pages).color(Messages.OTHER)));
    for (BanList.Entry entry : shown.subList((page - 1) * BANS_PER_PAGE, Math.min(shown.size(), page * BANS_PER_PAGE))) {
      String kind = switch (entry.kind()) {
        case NAME -> "";
        case ADDRESS -> " (address)";
        case ACCOUNT -> " (account)";
      };
      source.sendMessage(Text.of(entry.value()).color(Messages.BODY)
          .append(Text.of(kind + "  " + BanList.describeDuration(now - entry.createdAt()) + " ago").color(Messages.OTHER)));
      source.sendMessage(Text.of("  Reason: ").color(Messages.LABEL).append(Text.of(entry.reason()).color(Messages.BODY)));
      source.sendMessage(Text.of("  Banned by: ").color(Messages.LABEL).append(Text.of(entry.actor()).color(Messages.BODY))
          .append(Text.of("  Duration: ").color(Messages.LABEL)).append(Text.of(entry.remaining(now).map(left -> left + " left")
              .orElse("Permanent")).color(Messages.BODY)));
    }
    if (page < pages) Messages.info(source, "Next: /gbanlist " + (page + 1));
  }

  /** How a ban reads in a confirmation and in the list. */
  private static String describeBan(BanList.Entry entry) {
    return entry.permanent() ? "permanently"
        : "for " + BanList.describeDuration(entry.expiresAt() - System.currentTimeMillis());
  }

  /**
   * Kicks every online player the test picks out, and says how many that was. The test is given the
   * API player rather than the tracked one, since an address ban has to ask where they connected
   * from, which only the API player knows.
   */
  private static int kickMatching(PlayerManager players,
      java.util.function.Predicate<gg.tame.conduit.api.player.Player> test, String reason) {
    // Read the same way the login screen is, so colour codes in the [bans] template show as colour.
    var screen = gg.tame.conduit.config.StatusSettings.parseMotd(reason);
    int kicked = 0;
    for (TrackedPlayer tracked : players.all()) {
      if (!(tracked instanceof gg.tame.conduit.api.player.Player api)) continue;
      if (!test.test(api)) continue;
      api.disconnect(screen);
      kicked++;
    }
    return kicked;
  }

  /**
   * {@code /gwhitelist <on|off|add|remove|list|clear|status>}. Every one of them takes effect on the
   * next login attempt, with nothing reloaded and no file for the operator to edit.
   */
  private static void gwhitelist(CommandSource source, ConduitRuntime runtime, PlayerManager players, List<String> arguments) {
    if (runtime == null) { Messages.failure(source, "Runtime unavailable."); return; }
    Whitelist whitelist = runtime.whitelist();
    String action = arguments.isEmpty() ? "status" : arguments.getFirst().toLowerCase(Locale.ROOT);
    switch (action) {
      case "on", "enable" -> {
        if (whitelist.setEnabled(true)) {
          Messages.success(source, "Whitelist on. " + whitelist.size()
              + (whitelist.size() == 1 ? " player may join" : " players may join")
              + ", plus anyone with " + Permissions.WHITELIST_BYPASS + ".");
        } else {
          Messages.info(source, "The whitelist is already on.");
        }
      }
      case "off", "disable" -> {
        if (whitelist.setEnabled(false)) Messages.success(source, "Whitelist off. Anyone may join.");
        else Messages.info(source, "The whitelist is already off.");
      }
      case "add" -> {
        if (arguments.size() < 2) { Messages.info(source, "Usage: /gwhitelist add <player>"); return; }
        String name = arguments.get(1);
        if (whitelist.add(name)) Messages.success(source, "Added " + name + " to the whitelist.");
        else Messages.info(source, name + " is already on the whitelist.");
      }
      case "remove" -> {
        if (arguments.size() < 2) { Messages.info(source, "Usage: /gwhitelist remove <player>"); return; }
        String name = arguments.get(1);
        if (!whitelist.remove(name)) { Messages.failure(source, name + " is not on the whitelist."); return; }
        // Taking someone off while it is on is meant to keep them out, so it also puts them out.
        int kicked = whitelist.isEnabled()
            ? kickMatching(players, player -> player.username().equalsIgnoreCase(name),
                "You are not on this network's whitelist.")
            : 0;
        Messages.success(source, "Removed " + name + " from the whitelist"
            + (kicked > 0 ? " and kicked them" : "") + ".");
      }
      case "list" -> {
        List<String> names = whitelist.names();
        if (names.isEmpty()) { Messages.info(source, "The whitelist is empty."); return; }
        source.sendMessage(Text.of("Whitelist (" + names.size() + ")").color(Messages.BRAND).bold());
        source.sendMessage(Text.of(String.join(", ", names)).color(Messages.BODY));
      }
      case "clear" -> {
        int had = whitelist.clear();
        if (had == 0) Messages.info(source, "The whitelist was already empty.");
        else Messages.success(source, "Cleared the whitelist (" + had + " removed).");
      }
      case "status" -> source.sendMessage(Text.of("Whitelist: ").color(Messages.LABEL)
          .append(Text.of(whitelist.isEnabled() ? "on" : "off")
              .color(whitelist.isEnabled() ? Messages.OK : Messages.OTHER))
          .append(Text.of(" - " + whitelist.size()
              + (whitelist.size() == 1 ? " player" : " players")).color(Messages.BODY)));
      default -> Messages.info(source, "Usage: /gwhitelist <on|off|add|remove|list|clear|status>");
    }
  }

  private static List<String> completeGban(PlayerManager players, List<String> arguments) {
    if (arguments.size() <= 1) return completePlayers(players, arguments);
    if (arguments.size() == 2) return filtered(List.of("perm", "30m", "1h", "6h", "1d", "7d", "4w"), arguments.get(1));
    return List.of();
  }

  private static List<String> completeBanned(ConduitRuntime runtime, List<String> arguments) {
    if (runtime == null || arguments.size() > 1) return List.of();
    List<String> banned = new ArrayList<>();
    for (BanList.Entry entry : runtime.bans().active()) {
      // An account ban is lifted by unbanning the name it was made alongside, so a raw UUID is not
      // something to offer an operator.
      if (entry.kind() != BanList.Kind.ACCOUNT) banned.add(entry.value());
    }
    return filtered(banned, arguments.isEmpty() ? "" : arguments.getFirst());
  }

  private static List<String> completeWhitelist(ConduitRuntime runtime, PlayerManager players, List<String> arguments) {
    if (arguments.size() <= 1) {
      return filtered(List.of("on", "off", "add", "remove", "list", "clear", "status"),
          arguments.isEmpty() ? "" : arguments.getFirst());
    }
    if (arguments.size() == 2 && runtime != null) {
      String action = arguments.getFirst().toLowerCase(Locale.ROOT);
      // Adding offers who is online; removing offers who is actually on the list.
      if (action.equals("add")) return completePlayers(players, List.of(arguments.get(1)));
      if (action.equals("remove")) return filtered(runtime.whitelist().names(), arguments.get(1));
    }
    return List.of();
  }

  /** The entries of {@code options} that start with what has been typed, case-insensitively. */
  private static List<String> filtered(List<String> options, String typed) {
    String prefix = typed == null ? "" : typed.toLowerCase(Locale.ROOT);
    List<String> matches = new ArrayList<>();
    for (String option : options) if (option.toLowerCase(Locale.ROOT).startsWith(prefix)) matches.add(option);
    return matches;
  }

  private static void conduit(CommandSource source, ConduitRuntime runtime, ServerRegistry registry, List<String> arguments) {
    // Bare /conduit used to be /conduit info, so the one command a player is most likely to try
    // first told them nothing about the others. It now names them, and info stays where it is.
    if (arguments.isEmpty()) {
      usage(source);
      return;
    }
    String subcommand = arguments.getFirst().toLowerCase(Locale.ROOT);
    String node = SUBCOMMAND_NODES.get(ALIASES.getOrDefault(subcommand, subcommand));
    if (node != null && !Permissions.allows(source, node)) {
      Messages.permission(source);
      return;
    }
    switch (subcommand) {
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
      case "alert" -> alert(source, arguments.subList(1, arguments.size()));
      case "cache" -> cache(source, runtime, arguments.subList(1, arguments.size()));
      case "help" -> help(source);
      case "shutdown" -> shutdown(source, runtime, arguments.subList(1, arguments.size()));
      default -> Messages.info(source, "Unknown /conduit subcommand. Try /conduit help");
    }
  }

  /**
   * What bare {@code /conduit} answers.
   *
   * <p>It used to be one line naming every subcommand, separated by pipes, which at fourteen of them
   * was a paragraph of words with no shape: nothing said which of them looks at the proxy and which
   * changes it. They are grouped here instead, and only the groups this source may actually use are
   * shown -- a player with one node sees one line rather than the twenty they would be refused.
   */
  private static void usage(CommandSource source) {
    List<String> allowed = conduitSubcommands(source);
    if (allowed.isEmpty()) {
      Messages.info(source, "Usage: /conduit help");
      return;
    }
    source.sendMessage(Text.of(Conduit.BRAND + " " + Conduit.VERSION).color(Messages.BRAND).bold());
    source.sendMessage(Text.of("Proxy commands. Run one as /conduit <name>.").color(Messages.LABEL));
    for (Group group : CONDUIT_GROUPS) {
      List<String> mine = new ArrayList<>();
      for (String subcommand : group.subcommands()) if (allowed.contains(subcommand)) mine.add(subcommand);
      if (mine.isEmpty()) continue;
      source.sendMessage(Text.of("  " + pad(group.title()) + " ").color(Messages.LABEL)
          .append(Text.of(String.join(", ", mine)).color(Messages.BODY)));
    }
    if (source instanceof ConsoleCommandSource) {
      source.sendMessage(Text.of("  " + pad("Console") + " ").color(Messages.LABEL)
          .append(Text.of("shutdown").color(Messages.BODY)));
    }
    source.sendMessage(Text.of("/conduit help").color(Messages.BODY)
        .append(Text.of(" lists every command, not only these.").color(Messages.OTHER)));
  }

  /** A heading and the subcommands under it, for the overview bare /conduit prints. */
  private record Group(String title, List<String> subcommands) { }

  /**
   * The overview's groups, in the order they are shown: what the proxy is doing, what an operator
   * changes while it runs, and what is only reached for when something is wrong.
   */
  private static final List<Group> CONDUIT_GROUPS = List.of(
      new Group("Status", List.of("info", "servers", "health", "uptime", "metrics", "plugins")),
      new Group("Operate", List.of("maintenance", "drain", "undrain", "attack", "alert", "reload")),
      new Group("Diagnose", List.of("doctor", "diagnostics", "dump", "heap", "cache")));

  /** Group headings padded to one width, so the names beside them line up in a fixed-width chat. */
  private static String pad(String title) {
    StringBuilder padded = new StringBuilder(title);
    while (padded.length() < 8) padded.append(' ');
    return padded.toString();
  }

  /**
   * Stops the proxy gracefully, with every player shown {@code reason} when one is given. The console
   * only, with no node a permissions plugin could hand out: stopping the proxy is the operator's.
   */
  private static void shutdown(CommandSource source, ConduitRuntime runtime, List<String> reason) {
    if (!(source instanceof ConsoleCommandSource)) {
      Messages.failure(source, "Only the proxy's console can stop it.");
      return;
    }
    if (runtime == null) {
      Messages.failure(source, "Unable to shut down.");
      return;
    }
    Messages.info(source, "Shutting down.");
    if (reason.isEmpty()) runtime.shutdown();
    else runtime.shutdown(Text.of(String.join(" ", reason)));
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

  /**
   * {@code /conduit help}: every command this source may run, grouped and with a word on what each
   * one does.
   *
   * <p>It was a flat list of usage strings, network commands and proxy subcommands together, in the
   * order they happened to be written. Twenty-eight lines of that is a wall nobody reads, and it
   * never said what any of them were for. A group whose commands this source may not run is left out
   * entirely rather than shown empty.
   */
  private static void help(CommandSource source) {
    source.sendMessage(Text.of(Conduit.BRAND + " " + Conduit.VERSION).color(Messages.BRAND).bold());
    helpGroup(source, "Getting around", List.of(
        entry(null, "/server", "list the servers you may join"),
        entry(null, "/server <server>", "move yourself there"),
        entry(null, "/hub", "back to the lobby"),
        entry(null, "/ping", "your latency to the proxy")));
    helpGroup(source, "Players", List.of(
        entry(Permissions.GLIST, "/glist", "who is online, by server"),
        entry(Permissions.PLIST, "/plist <server>", "who is on one server"),
        entry(Permissions.FIND, "/find <player>", "which server someone is on"),
        entry(Permissions.SEND, "/send <player|server|current> <server>", "move someone else"),
        entry(Permissions.ALERT, "/alert <message>", "tell the whole network")));
    helpGroup(source, "Moderation", List.of(
        entry(Permissions.GKICK, "/gkick <player> [reason]", "disconnect someone once"),
        entry(Permissions.GBAN, "/gban <player|address> [duration] [reason]", "keep them out; no duration is permanent"),
        entry(Permissions.GBAN, "/gunban <player|address>", "lift a ban"),
        entry(Permissions.GBAN, "/gbanlist [page]", "who is banned, by whom, and for how long"),
        entry(Permissions.GWHITELIST, "/gwhitelist <on|off|add|remove|list|clear|status>", "close the network to a list")));
    helpGroup(source, "Proxy status", List.of(
        entry(Permissions.INFO, "/conduit info", "version, your server, player counts"),
        entry(Permissions.SERVERS, "/conduit servers", "every backend and whether it is up"),
        entry(Permissions.HEALTH, "/conduit health", "health checks, with their counters"),
        entry(Permissions.UPTIME, "/conduit uptime", "how long this proxy has been up"),
        entry(Permissions.METRICS, "/conduit metrics", "counters this proxy keeps"),
        entry(Permissions.PLUGINS, "/conduit plugins", "what is loaded")));
    helpGroup(source, "Proxy operations", List.of(
        entry(Permissions.MAINTENANCE, "/conduit maintenance <on|off|status>", "close the network with a message"),
        entry(Permissions.DRAIN, "/conduit drain <server>", "stop sending players to one backend"),
        entry(Permissions.DRAIN, "/conduit undrain <server>", "start again"),
        entry(Permissions.ATTACK, "/conduit attack <on|off|status>", "stricter flood limits, without editing config"),
        entry(Permissions.RELOAD, "/conduit reload", "re-read conduit.toml")));
    helpGroup(source, "When something is wrong", List.of(
        entry(Permissions.DOCTOR, "/conduit doctor", "what looks wrong with this setup"),
        entry(Permissions.DIAGNOSTICS, "/conduit diagnostics", "the long form of it"),
        entry(Permissions.DUMP, "/conduit dump", "write a support bundle to disk"),
        entry(Permissions.HEAP, "/conduit heap", "memory, right now"),
        entry(Permissions.CACHE, "/conduit cache invalidate <source>", "drop a cache")));
    if (source instanceof ConsoleCommandSource) {
      helpGroup(source, "Console only", List.of(
          entry(null, "/conduit shutdown [reason]", "stop the proxy, telling players why")));
    }
  }

  /** One line of help: the node it needs, how it is typed, and what it does. */
  private record HelpEntry(String permission, String usage, String description) { }

  private static HelpEntry entry(String permission, String usage, String description) {
    return new HelpEntry(permission, usage, description);
  }

  /** Prints a heading and its lines, or nothing at all when this source may run none of them. */
  private static void helpGroup(CommandSource source, String title, List<HelpEntry> entries) {
    List<HelpEntry> mine = new ArrayList<>();
    for (HelpEntry entry : entries) {
      if (entry.permission() == null || Permissions.allows(source, entry.permission())) mine.add(entry);
    }
    if (mine.isEmpty()) return;
    source.sendMessage(Text.empty());
    source.sendMessage(Text.of(title).color(Messages.BRAND));
    for (HelpEntry entry : mine) {
      source.sendMessage(Text.of("  " + entry.usage()).color(Messages.BODY)
          .append(Text.of("  " + entry.description()).color(Messages.OTHER)));
    }
  }

  /**
   * The /conduit subcommands {@code source} may run, in help order, with help itself only when there
   * is something else: for a player with none, /conduit is not there at all. The console's shutdown is
   * not among them.
   */
  public static List<String> conduitSubcommands(gg.tame.conduit.api.permission.PermissionSubject source) {
    List<String> allowed = new ArrayList<>();
    for (String subcommand : CONDUIT_SUBCOMMANDS) {
      String node = SUBCOMMAND_NODES.get(subcommand);
      if (node != null && Permissions.allows(source, node)) allowed.add(subcommand);
    }
    if (!allowed.isEmpty()) allowed.add("help");
    return allowed;
  }

  private static void health(CommandSource source, ConduitRuntime runtime, ServerRegistry registry) {
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

  /**
   * {@code /conduit alert [message]}: posts a test alert to the configured webhook and reports how it
   * went, so a webhook that never fires can be found out from the console instead of guessed at.
   */
  private static void alert(CommandSource source, List<String> words) {
    if (!gg.tame.conduit.ops.Alerts.enabled()) {
      Messages.failure(source, "No webhook is set. Put [alerts] webhook-url = \"https://...\" in conduit.toml (the [alerts] header must be uncommented too) and /conduit reload.");
      return;
    }
    String message = words.isEmpty() ? "Test alert from Conduit, sent by " + source.username() + "." : String.join(" ", words);
    Messages.info(source, "Sending the alert...");
    gg.tame.conduit.ops.Alerts.send(message).thenAccept(outcome -> {
      if (outcome.startsWith("delivered")) Messages.success(source, "Alert " + outcome + ".");
      else Messages.failure(source, "Alert not delivered: " + outcome);
    });
  }

  private static void attack(CommandSource source, ConduitRuntime runtime, List<String> arguments) {
    if (runtime == null) {
      Messages.failure(source, "Runtime unavailable.");
      return;
    }
    if (arguments.isEmpty() || arguments.getFirst().equalsIgnoreCase("status")) {
      var mode = runtime.security().attackMode();
      boolean active = mode.isActive();
      var line = Text.of("Attack mode: ").color(Messages.LABEL)
          .append(Text.of(active ? "ON" : "OFF").color(active ? Messages.WARN : Messages.OK).bold());
      if (active) line = line.append(Text.of(mode.isAutomatic()
          ? " (switched on automatically by the connection rate; lifts after a quiet minute)"
          : " (switched on by command)").color(Messages.BODY));
      source.sendMessage(line);
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
    Optional<InetAddress> address = BanList.literalAddress(arguments.get(1));
    if (address.isEmpty()) {
      Messages.failure(source, "Not an IP address: " + arguments.get(1));
      return;
    }
    boolean removed = runtime.modded().invalidateCache(address.get());
    if (removed) Messages.success(source, "Mod handshake cache invalidated for source.");
    else Messages.info(source, "No cache entries for that source.");
  }

  private static void drain(CommandSource source, ConduitRuntime runtime, ServerRegistry registry, List<String> arguments, boolean enable) {
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
    try {
      Path file = diagnosticsDirectory(runtime).resolve("conduit-" + stamp() + ".txt");
      // Counters, versions and server names. No addresses, no config, no player data: this is the
      // one dump an operator is meant to be able to paste into an issue.
      StringBuilder body = new StringBuilder();
      body.append("Conduit ").append(Conduit.VERSION).append('\n');
      body.append("uptimeMs=").append(runtime == null ? 0 : runtime.uptimeMillis()).append('\n');
      body.append("metrics=").append(ConduitMetrics.current().snapshot()).append('\n');
      for (String name : registry.names()) body.append("server=").append(name).append('\n');
      Files.writeString(file, body.toString(), StandardOpenOption.CREATE_NEW);
      Messages.success(source, "Wrote dump to " + file.toAbsolutePath());
      Messages.info(source, "Counters and server names only - no addresses, secrets, or player data.");
    } catch (IOException exception) {
      Messages.failure(source, "Dump failed.");
    }
  }

  private static void heap(CommandSource source, ConduitRuntime runtime) {
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
  private static final AtomicReference<Instant> LAST_STAMP = new AtomicReference<>(Instant.EPOCH);
  /** A timestamp that never repeats: Windows' clock can stand still for a millisecond, and a repeated name overwrote the dump before it. */
  private static String stamp() {
    Instant now = Instant.now();
    return LAST_STAMP.accumulateAndGet(now, (last, current) -> current.isAfter(last) ? current : last.plusNanos(1))
        .toString().replace(':', '-');
  }

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
      workers.add(gg.tame.conduit.network.SocketThreads.start(() -> {
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

  /**
   * Tab completion for {@code /conduit}, past the subcommand as well as at it.
   *
   * <p>Only the subcommand used to complete, so {@code /conduit drain <TAB>} offered nothing and the
   * server had to be named from memory -- the same for every subcommand that takes an argument. The
   * console is included because it completes through this method too, and {@code shutdown} appears
   * only there: it is refused for anyone else, and suggesting it to a player is an offer Conduit
   * will not honour.
   */
  private static List<String> completeConduit(CommandSource source, ServerRegistry registry, List<String> arguments) {
    if (arguments.size() <= 1) {
      List<String> subcommands = new ArrayList<>(conduitSubcommands(source));
      if (source instanceof ConsoleCommandSource) subcommands.add("shutdown");
      return prefix(subcommands, arguments.isEmpty() ? "" : arguments.getFirst());
    }
    String subcommand = arguments.getFirst().toLowerCase(Locale.ROOT);
    String node = SUBCOMMAND_NODES.get(ALIASES.getOrDefault(subcommand, subcommand));
    if (node == null || !Permissions.allows(source, node)) return List.of();
    if (arguments.size() == 2) {
      return switch (subcommand) {
        case "drain", "undrain" -> prefix(registry.names(), arguments.get(1));
        case "maintenance", "attack", "attackmode" -> prefix(List.of("on", "off", "status"), arguments.get(1));
        case "cache" -> prefix(List.of("invalidate"), arguments.get(1));
        default -> List.of();
      };
    }
    // /conduit cache invalidate takes the IP address a connection came from, which is not something
    // there is a list of to suggest.
    return List.of();
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
