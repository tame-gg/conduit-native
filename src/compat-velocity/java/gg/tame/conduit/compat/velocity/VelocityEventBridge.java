package gg.tame.conduit.compat.velocity;

import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.network.HandshakeIntent;
import com.velocitypowered.api.network.ProtocolState;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.api.util.Favicon;
import gg.tame.conduit.api.event.Subscribe;
import gg.tame.conduit.api.event.player.PlayerDisconnectEvent;
import gg.tame.conduit.api.event.player.PlayerInitialServerEvent;
import gg.tame.conduit.api.event.player.PlayerKickedFromServerEvent;
import gg.tame.conduit.api.event.player.PlayerKickedFromServerEvent.KickResult;
import gg.tame.conduit.api.event.player.PlayerLoginEvent;
import gg.tame.conduit.api.event.player.PlayerPostLoginEvent;
import gg.tame.conduit.api.event.player.PlayerServerConnectEvent;
import gg.tame.conduit.api.event.player.PlayerServerConnectedEvent;
import gg.tame.conduit.api.event.player.PlayerServerSwitchEvent;
import gg.tame.conduit.api.event.proxy.ProxyStartEvent;
import gg.tame.conduit.api.event.proxy.ServerListPingEvent;
import gg.tame.conduit.api.text.Text;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;

/**
 * Conduit's native events, raised again as their Velocity counterparts. Where Velocity plugins can
 * change the outcome (deny a login, cancel or redirect a connection, drop a message) the native
 * thread waits for them and the Velocity result is applied to the native event. Velocity plugin
 * code itself never runs on the native thread.
 */
final class VelocityEventBridge {
  private final VelocityEnvironment environment;
  VelocityEventBridge(VelocityEnvironment environment) { this.environment = environment; }

  private boolean listening(Class<?> type) { return environment.events.listening(type); }

  @Subscribe public void onStart(ProxyStartEvent event) {
    if (listening(ProxyInitializeEvent.class)) environment.fireAndWait(new ProxyInitializeEvent());
  }
  @Subscribe public void onShutdown(gg.tame.conduit.api.event.proxy.ProxyShutdownEvent event) {
    for (var plugin : environment.plugins.getPlugins()) ((VelocityPluginHost.Container) plugin).shutdownDelivered = true;
    if (listening(ProxyShutdownEvent.class)) environment.fireAndWait(new ProxyShutdownEvent());
  }

  /** Authenticated and about to go to a backend: permissions are set up first, then LoginEvent, as on Velocity. */
  @Subscribe public void onLogin(PlayerLoginEvent event) {
    if (!event.allowed()) return;
    VelocityPlayer player = environment.player(event.player());
    environment.permissions.setUp(player);
    if (!listening(LoginEvent.class)) return;
    LoginEvent login = environment.fireAndWait(new LoginEvent(player));
    if (!login.getResult().isAllowed()) {
      event.deny(Texts.toConduit(login.getResult().getReasonComponent().orElse(net.kyori.adventure.text.Component.empty())));
    }
  }
  /** A refused login never gets a disconnect event, so what was set up for it goes here. */
  @Subscribe(order = Subscribe.Order.LAST) public void onLoginDecided(PlayerLoginEvent event) {
    if (!event.allowed()) environment.permissions.forget(event.player());
  }
  @Subscribe public void onInitialServer(PlayerInitialServerEvent event) {
    if (!listening(PlayerChooseInitialServerEvent.class)) return;
    RegisteredServer offered = event.initialServer().map(environment::server).orElse(null);
    PlayerChooseInitialServerEvent choose = environment.fireAndWait(
        new PlayerChooseInitialServerEvent(environment.player(event.player()), offered));
    RegisteredServer chosen = choose.getInitialServer().orElse(null);
    if (chosen != null && !chosen.equals(offered)) event.setInitialServer(environment.nativeServer(chosen));
  }
  @Subscribe public void onPostLogin(PlayerPostLoginEvent event) {
    VelocityPlayer player = environment.player(event.player());
    player.loggedIn = true;
    if (listening(PostLoginEvent.class)) environment.fireAndWait(new PostLoginEvent(player));
  }
  @Subscribe public void onDisconnect(PlayerDisconnectEvent event) {
    VelocityPlayer player = environment.player(event.player());
    try {
      if (listening(DisconnectEvent.class)) {
        environment.fireAndWait(new DisconnectEvent(player,
            player.loggedIn ? DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN : DisconnectEvent.LoginStatus.PRE_SERVER_JOIN));
      }
    } finally {
      environment.forget(event.player());
    }
  }

  @Subscribe public void onConnect(PlayerServerConnectEvent event) {
    if (event.cancelled() || !listening(ServerPreConnectEvent.class)) return;
    VelocityRegisteredServer target = environment.server(event.target());
    ServerPreConnectEvent connect = environment.fireAndWait(new ServerPreConnectEvent(environment.player(event.player()), target,
        event.source().map(environment::server).orElse(null)));
    if (!connect.getResult().isAllowed()) {
      event.setCancelled(true);
      return;
    }
    RegisteredServer chosen = connect.getResult().getServer().orElse(target);
    if (!chosen.equals(target)) event.setTarget(environment.nativeServer(chosen));
  }
  @Subscribe public void onConnected(PlayerServerConnectedEvent event) {
    VelocityPlayer player = environment.player(event.player());
    VelocityRegisteredServer previous = event.source().map(environment::server).orElse(null);
    player.previousServer = previous;
    if (listening(ServerConnectedEvent.class)) {
      environment.fireAndWait(new ServerConnectedEvent(player, environment.server(event.target()), previous));
    }
  }
  @Subscribe public void onSwitched(PlayerServerSwitchEvent event) {
    if (!listening(ServerPostConnectEvent.class)) return;
    environment.fireAndWait(new ServerPostConnectEvent(environment.player(event.player()), event.source().map(environment::server).orElse(null)));
  }

  /**
   * The native result, as a Velocity one and back. Notify outside a connect is a disconnect with
   * that message on both sides; a redirect without a message shows the kick reason, as the API says.
   */
  @Subscribe public void onKicked(PlayerKickedFromServerEvent event) {
    if (!listening(KickedFromServerEvent.class)) return;
    KickedFromServerEvent.ServerKickResult offered = velocityResult(event.result());
    KickedFromServerEvent kicked = environment.fireAndWait(new KickedFromServerEvent(environment.player(event.player()),
        environment.server(event.server()), event.reason().map(Texts::toAdventure).orElse(null), event.duringConnect(), offered));
    // Left alone, Conduit's own result stands untouched: the backend's reason exactly as it wrote it.
    if (kicked.getResult() == offered) return;
    try {
      switch (kicked.getResult()) {
        case KickedFromServerEvent.DisconnectPlayer disconnect -> event.setResult(new KickResult.Disconnect(Texts.toConduit(disconnect.getReasonComponent())));
        case KickedFromServerEvent.Notify notify -> event.setResult(new KickResult.Notify(Texts.toConduit(notify.getMessageComponent())));
        case KickedFromServerEvent.RedirectPlayer redirect -> {
          Component message = redirect.getMessageComponent();
          Optional<Text> shown = message == null ? event.reason() : Component.empty().equals(message) ? Optional.empty() : Optional.of(Texts.toConduit(message));
          event.setResult(new KickResult.Redirect(environment.nativeServer(redirect.getServer()), shown));
        }
        default -> environment.log.warning("A Velocity plugin gave KickedFromServerEvent a result of its own type; Conduit kept its own");
      }
    } catch (RuntimeException unusable) {
      // A redirect to a server the proxy does not have, say: the player gets what Conduit would do.
      environment.log.log(Level.WARNING, "Velocity KickedFromServerEvent result for " + event.player().username() + " was not usable", unusable);
    }
  }
  private KickedFromServerEvent.ServerKickResult velocityResult(KickResult result) {
    return switch (result) {
      case KickResult.Disconnect disconnect -> KickedFromServerEvent.DisconnectPlayer.create(Texts.toAdventure(disconnect.reason()));
      case KickResult.Notify notify -> KickedFromServerEvent.Notify.create(Texts.toAdventure(notify.message()));
      case KickResult.Redirect redirect -> KickedFromServerEvent.RedirectPlayer.create(environment.server(redirect.server()),
          redirect.message().map(Texts::toAdventure).orElse(Component.empty()));
    };
  }

  /**
   * The server-list answer as a ServerPing, and whatever the plugins leave in it back. A denied
   * result sends no answer, and a ServerPing with no players hides the counts. Mod info has no place
   * in Conduit's answer, so that part of a plugin's ServerPing is dropped.
   */
  @Subscribe public void onPing(ServerListPingEvent event) {
    if (event.cancelled() || !listening(ProxyPingEvent.class)) return;
    ServerPing offered = new ServerPing(new ServerPing.Version(event.versionProtocol(), event.versionName()),
        event.playersHidden() ? null : new ServerPing.Players(event.onlinePlayers(), event.maxPlayers(),
            event.samplePlayers().stream().map(player -> new ServerPing.SamplePlayer(player.name(), player.uniqueId())).toList()),
        Texts.toAdventure(event.description()), event.favicon().map(Favicon::new).orElse(null));
    ProxyPingEvent ping = environment.fireAndWait(new ProxyPingEvent(new PingConnection(event), offered));
    if (!ping.getResult().isAllowed()) {
      event.setCancelled(true);
      return;
    }
    ServerPing answer = ping.getPing();
    if (answer == null) return;
    event.setDescription(Texts.toConduit(answer.getDescriptionComponent()));
    event.setVersionName(answer.getVersion().getName());
    event.setVersionProtocol(answer.getVersion().getProtocol());
    event.setPlayersHidden(answer.getPlayers().isEmpty());
    answer.getPlayers().ifPresent(players -> {
      event.setOnlinePlayers(players.getOnline());
      event.setMaxPlayers(players.getMax());
      event.setSamplePlayers(players.getSample().stream()
          .map(player -> new ServerListPingEvent.SamplePlayer(player.getName(), player.getId())).toList());
    });
    event.setFavicon(answer.getFavicon().map(Favicon::getBase64Url));
  }
  /**
   * Who is asking for the server list: a status connection, before any login. A class, not a
   * record, so no public accessor hands a plugin the native event.
   */
  private static final class PingConnection implements InboundConnection {
    private final ServerListPingEvent ping;
    PingConnection(ServerListPingEvent ping) { this.ping = ping; }
    @Override public InetSocketAddress getRemoteAddress() { return ping.remoteAddress(); }
    @Override public Optional<InetSocketAddress> getVirtualHost() {
      return ping.virtualHost().map(host -> InetSocketAddress.createUnresolved(host, ping.virtualPort()));
    }
    @Override public Optional<String> getRawVirtualHost() { return ping.virtualHost(); }
    @Override public boolean isActive() { return true; }
    @Override public ProtocolVersion getProtocolVersion() { return ProtocolVersion.getProtocolVersion(ping.protocolVersion()); }
    @Override public ProtocolState getProtocolState() { return ProtocolState.STATUS; }
    @Override public HandshakeIntent getHandshakeIntent() { return HandshakeIntent.STATUS; }
  }

  @Subscribe public void onChat(gg.tame.conduit.api.event.player.PlayerChatEvent event) {
    if (event.cancelled() || !listening(PlayerChatEvent.class)) return;
    PlayerChatEvent chat = environment.fireAndWait(new PlayerChatEvent(environment.player(event.player()), event.message()));
    if (!chat.getResult().isAllowed()) event.setCancelled(true);
    else if (chat.getResult().getMessage().isPresent()) {
      environment.log.warning("A Velocity plugin rewrote a chat message; Conduit cannot change chat, so it was sent unchanged");
    }
  }
  @Subscribe public void onCommand(gg.tame.conduit.api.event.command.CommandExecuteEvent event) {
    if (event.cancelled() || !listening(CommandExecuteEvent.class)) return;
    var source = event.source() instanceof gg.tame.conduit.api.player.Player player ? environment.player(player) : environment.console;
    CommandExecuteEvent command = environment.fireAndWait(new CommandExecuteEvent(source, event.command()));
    if (!command.getResult().isAllowed()) event.setCancelled(true);
    else if (command.getResult().getCommand().isPresent() || command.getResult().isForwardToServer()) {
      environment.log.warning("A Velocity plugin rewrote or forwarded /" + event.command() + "; Conduit cannot, so it ran unchanged");
    }
  }
  @Subscribe public void onPluginMessage(gg.tame.conduit.api.event.messaging.PluginMessageEvent event) {
    ChannelIdentifier channel = environment.channels.find(event.channel());
    if (channel == null || event.cancelled() || !listening(PluginMessageEvent.class)) return;
    VelocityPlayer player = environment.player(event.player());
    var connection = player.getCurrentServer().orElse(null);
    if (connection == null) return;
    boolean fromClient = event.direction() == gg.tame.conduit.api.event.messaging.PluginMessageEvent.Direction.CLIENT_TO_PROXY;
    PluginMessageEvent message = environment.fireAndWait(fromClient
        ? new PluginMessageEvent(player, connection, channel, event.data())
        : new PluginMessageEvent(connection, player, channel, event.data()));
    if (!message.getResult().isAllowed()) event.setCancelled(true);
  }
}
