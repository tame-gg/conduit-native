package gg.tame.conduit.compat.velocity;

import gg.tame.conduit.api.ConduitProxy;
import gg.tame.conduit.api.plugin.ConduitPlugin;
import gg.tame.conduit.api.plugin.PluginDescription;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Everything one Conduit proxy's Velocity layer shares. Built on the native API only.
 *
 * <p>Threads: Velocity plugin code never runs on a player's connection thread. Event handlers,
 * command bodies, completions and task bodies run on {@link #work}, a pool of daemon platform
 * threads owned here. A connection thread that needs a plugin's answer (may this player log in?)
 * waits for it, for at most {@link #WAIT_MS}.
 */
final class VelocityEnvironment {
  static final long WAIT_MS = 10_000;

  final ConduitProxy conduit;
  final Logger log = Logger.getLogger("velocity");
  final ExecutorService work;
  final Set<VelocityClassLoader> loaders = VelocityClassLoader.newRegistry();
  final VelocityPluginHost plugins = new VelocityPluginHost();
  final VelocityEventBus events = new VelocityEventBus(this);
  final VelocityChannelRegistrar channels = new VelocityChannelRegistrar();
  final VelocityCommandHost commands = new VelocityCommandHost(this);
  final VelocitySchedulerHost scheduler = new VelocitySchedulerHost(this);
  final VelocityPermissions permissions = new VelocityPermissions(this);
  final VelocityConsole console;
  final VelocityProxyServer proxy;
  /** Owns the adapter's one native listener; the adapter is not itself a plugin in /plugins. */
  final ConduitPlugin owner = new ConduitPlugin() { };
  private final ConcurrentHashMap<gg.tame.conduit.api.player.Player, VelocityPlayer> players = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, VelocityRegisteredServer> servers = new ConcurrentHashMap<>();

  VelocityEnvironment(ConduitProxy conduit) {
    this.conduit = conduit;
    AtomicInteger threads = new AtomicInteger();
    this.work = Executors.newCachedThreadPool(runnable -> {
      Thread thread = new Thread(runnable, "conduit-velocity-" + threads.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    });
    this.console = new VelocityConsole(conduit.console());
    this.proxy = new VelocityProxyServer(this);
    owner.attach(new PluginDescription("velocity-compat", "Velocity compatibility", conduit.version(),
        VelocityBoot.class.getName(), 1, List.of()), conduit, log, null, conduit.scheduler());
  }

  /**
   * One wrapper per joined player, dropped at DisconnectEvent. A player still logging in gets a
   * fresh one each time: a login that is refused or never reaches a backend raises no
   * disconnect, and a cached wrapper for it would never be released.
   */
  VelocityPlayer player(gg.tame.conduit.api.player.Player player) {
    if (player == null) return null;
    VelocityPlayer known = players.get(player);
    if (known != null) return known;
    boolean joined = conduit.player(player.uniqueId()).filter(live -> live == player).isPresent();
    return joined ? players.computeIfAbsent(player, key -> new VelocityPlayer(this, key)) : new VelocityPlayer(this, player);
  }
  void forget(gg.tame.conduit.api.player.Player player) {
    players.remove(player);
    permissions.forget(player);
  }

  /** One wrapper per server name while its address stays the same, so plugins can compare them. */
  VelocityRegisteredServer server(gg.tame.conduit.api.server.RegisteredServer server) {
    if (server == null) return null;
    return servers.compute(server.getName().toLowerCase(Locale.ROOT), (name, known) ->
        known != null && known.nativeServer().getAddress().equals(server.getAddress()) ? known : new VelocityRegisteredServer(this, server));
  }
  gg.tame.conduit.api.server.RegisteredServer nativeServer(com.velocitypowered.api.proxy.server.RegisteredServer server) {
    if (server instanceof VelocityRegisteredServer ours) return ours.nativeServer();
    if (server == null) throw new IllegalArgumentException("server is required");
    // A RegisteredServer the plugin built itself: only its name can identify a real one.
    return conduit.servers().getServer(server.getServerInfo().getName())
        .orElseThrow(() -> new IllegalArgumentException("server " + server.getServerInfo().getName() + " is not registered with the proxy"));
  }

  /** Fires on the adapter's threads and waits, bounded, for every handler to finish. */
  <E> E fireAndWait(E event) {
    await(events.fire(event), event.getClass().getSimpleName());
    return event;
  }
  /** False when the wait ran out; the caller then carries on with the event as the handlers left it. */
  boolean await(CompletableFuture<?> future, String what) {
    try {
      future.get(WAIT_MS, TimeUnit.MILLISECONDS);
      return true;
    } catch (TimeoutException slow) {
      log.warning("Velocity plugins took over " + WAIT_MS + " ms to handle " + what + "; continuing without them");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } catch (Exception failed) {
      log.log(Level.WARNING, "Velocity handling of " + what + " failed", failed);
    }
    return false;
  }
}
