package gg.tame.conduit.compat.velocity;

import gg.tame.conduit.api.event.Subscribe;
import gg.tame.conduit.api.event.player.PlayerAuthenticatedEvent;
import gg.tame.conduit.api.event.player.PlayerDisconnectEvent;
import gg.tame.conduit.api.event.player.PlayerLoginEvent;
import gg.tame.conduit.api.event.player.PlayerPostLoginEvent;
import gg.tame.conduit.api.event.player.PlayerServerConnectEvent;
import gg.tame.conduit.api.event.player.PlayerServerConnectedEvent;
import gg.tame.conduit.api.event.player.PlayerServerSwitchFailedEvent;
import gg.tame.conduit.api.event.proxy.ProxyShutdownEvent;
import gg.tame.conduit.api.event.proxy.ProxyStartEvent;
import gg.tame.conduit.api.plugin.ConduitPlugin;
import gg.tame.conduit.api.plugin.PluginDescription;
import gg.tame.conduit.plugin.ExternalJarHandler;
import gg.tame.conduit.runtime.ConduitRuntime;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.Player;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

final class VelocityEnvironment {
  private final ConduitRuntime runtime;
  private final VelocityProxyServer proxy;
  private final VelocityEventBus events;
  private final VelocityPluginHost plugins;
  private final VelocityCommandHost commands;
  private final VelocitySchedulerHost scheduler;
  private final VelocityPluginLoader loader;
  private final ConcurrentHashMap<gg.tame.conduit.api.player.Player, Player> players = new ConcurrentHashMap<>();
  private final ConduitPlugin bridgePlugin;
  VelocityEnvironment(ConduitRuntime runtime) {
    this.runtime = runtime;
    this.events = new VelocityEventBus();
    this.plugins = new VelocityPluginHost();
    this.scheduler = new VelocitySchedulerHost();
    this.commands = new VelocityCommandHost(runtime, this);
    this.proxy = new VelocityProxyServer(runtime, this);
    this.loader = new VelocityPluginLoader(this);
    this.bridgePlugin = new ConduitPlugin() {};
    bridgePlugin.attach(new PluginDescription("velocity-compat", "Velocity Compatibility", "1", "internal", 1, List.of()),
        runtime, Logger.getLogger("velocity-compat"), Path.of("plugins", "velocity-compat"), runtime.scheduler());
  }
  ConduitRuntime runtime() { return runtime; }
  VelocityProxyServer proxy() { return proxy; }
  VelocityEventBus events() { return events; }
  VelocityPluginHost plugins() { return plugins; }
  VelocityCommandHost commands() { return commands; }
  VelocitySchedulerHost scheduler() { return scheduler; }
  ExternalJarHandler loader() { return loader; }
  Player wrap(gg.tame.conduit.api.player.Player player) {
    return players.computeIfAbsent(player, nativePlayer -> new VelocityPlayer(this, nativePlayer));
  }
  VelocityRegisteredServer wrapServer(gg.tame.conduit.api.server.RegisteredServer server) {
    return server == null ? null : new VelocityRegisteredServer(this, server);
  }
  void attachNativeEvents() {
    runtime.events().register(bridgePlugin, this);
  }
  void shutdown() {
    plugins.disableAll();
    scheduler.shutdown();
  }
  @Subscribe public void onStart(ProxyStartEvent event) {
    events.fire(new ProxyInitializeEvent());
  }
  @Subscribe public void onStop(ProxyShutdownEvent event) {
    events.fire(new com.velocitypowered.api.event.proxy.ProxyShutdownEvent());
    shutdown();
  }
  @Subscribe public void onLogin(PlayerLoginEvent event) {
    LoginEvent velocity = new LoginEvent(wrap(event.player()));
    events.fire(velocity);
    if (!velocity.getResult().isAllowed()) {
      event.player().disconnect(velocity.getResult().getReasonComponent().map(Texts::plain).orElse("Disconnected"));
    }
  }
  @Subscribe public void onAuth(PlayerAuthenticatedEvent event) { wrap(event.player()); }
  @Subscribe public void onPostLogin(PlayerPostLoginEvent event) {
    events.fire(new PostLoginEvent(wrap(event.player())));
  }
  @Subscribe public void onDisconnect(PlayerDisconnectEvent event) {
    Player wrapped = wrap(event.player());
    events.fire(new DisconnectEvent(wrapped, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));
    players.remove(event.player());
  }
  @Subscribe public void onConnect(PlayerServerConnectEvent event) {
    var target = wrapServer(event.target());
    var previous = event.source().map(this::wrapServer).orElse(null);
    ServerPreConnectEvent velocity = new ServerPreConnectEvent(wrap(event.player()), target, previous);
    events.fire(velocity);
    if (!velocity.getResult().isAllowed()) event.setCancelled(true);
  }
  @Subscribe public void onConnected(PlayerServerConnectedEvent event) {
    events.fire(new ServerConnectedEvent(wrap(event.player()), wrapServer(event.target()), event.source().map(this::wrapServer).orElse(null)));
  }
  @Subscribe public void onSwitchFailed(PlayerServerSwitchFailedEvent event) { }
}
