// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.compat.velocity;

import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.PlayerClientBrandEvent;
import com.velocitypowered.api.event.player.PlayerSettingsChangedEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.player.TabCompleteEvent;
import com.velocitypowered.api.event.proxy.ListenerBoundEvent;
import com.velocitypowered.api.event.proxy.ListenerCloseEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.event.proxy.ProxyPreShutdownEvent;
import com.velocitypowered.api.event.proxy.ProxyReloadEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.network.HandshakeIntent;
import com.velocitypowered.api.network.ListenerType;
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
import gg.tame.conduit.api.event.player.PlayerSetupEvent;
import gg.tame.conduit.api.event.proxy.ProxyStartEvent;
import gg.tame.conduit.api.event.proxy.ServerListPingEvent;
import gg.tame.conduit.api.event.proxy.ServerRegisteredEvent;
import gg.tame.conduit.api.event.proxy.ServerUnregisteredEvent;
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
    // After initialization, where plugins register their listeners; the accept loop starts next.
    if (listening(ListenerBoundEvent.class)) environment.events.fire(new ListenerBoundEvent(environment.conduit.boundAddress(), ListenerType.MINECRAFT));
  }
  @Subscribe public void onShutdown(gg.tame.conduit.api.event.proxy.ProxyShutdownEvent event) {
    for (var plugin : environment.plugins.getPlugins()) ((VelocityPluginHost.Container) plugin).shutdownDelivered = true;
    if (listening(ProxyShutdownEvent.class)) environment.fireAndWait(new ProxyShutdownEvent());
  }

  /**
   * Authenticated, nothing decided yet: permissions are set up here, before Conduit's maintenance
   * check and LoginEvent ask about them, as Velocity sets them up before LoginEvent.
   */
  @Subscribe public void onSetup(PlayerSetupEvent event) {
    environment.permissions.setUp(environment.player(event.player()));
  }
  /** About to go to a backend; not fired for a player maintenance refused. */
  @Subscribe public void onLogin(PlayerLoginEvent event) {
    if (!event.allowed() || !listening(LoginEvent.class)) return;
    LoginEvent login = environment.fireAndWait(new LoginEvent(environment.player(event.player())));
    if (!login.getResult().isAllowed()) {
      event.deny(Texts.toConduit(login.getResult().getReasonComponent().orElse(net.kyori.adventure.text.Component.empty())));
    }
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
    if (listening(PostLoginEvent.class)) environment.fireAndWait(new PostLoginEvent(environment.player(event.player())));
  }
  /**
   * Every player set up gets one, refused logins included, and the adapter lets go of them here. A
   * second login of a connected player is refused before any plugin sees it, so CONFLICTING_LOGIN is
   * only ever a login that a newer one displaced before it finished (kick-existing-players).
   */
  @Subscribe public void onDisconnect(PlayerDisconnectEvent event) {
    try {
      if (listening(DisconnectEvent.class)) {
        environment.fireAndWait(new DisconnectEvent(environment.player(event.player()), switch (event.loginStatus()) {
          case SUCCESSFUL_LOGIN -> DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN;
          case PRE_SERVER_JOIN -> DisconnectEvent.LoginStatus.PRE_SERVER_JOIN;
          case CANCELLED_BY_PROXY -> DisconnectEvent.LoginStatus.CANCELLED_BY_PROXY;
          case CANCELLED_BY_USER -> DisconnectEvent.LoginStatus.CANCELLED_BY_USER_BEFORE_COMPLETE;
          case CONFLICTING_LOGIN -> DisconnectEvent.LoginStatus.CONFLICTING_LOGIN;
        }));
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
    // After every connection, the first one included (no previous server): plugins such as resource
    // pack senders wait for it to act on a player who just joined. Nothing depends on its outcome.
    if (listening(ServerPostConnectEvent.class)) environment.events.fire(new ServerPostConnectEvent(player, previous));
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

  /** Told, not asked, as Velocity does not wait for it either: the ping or login goes on at once. */
  @Subscribe public void onHandshake(gg.tame.conduit.api.event.proxy.ConnectionHandshakeEvent event) {
    if (!listening(com.velocitypowered.api.event.connection.ConnectionHandshakeEvent.class)) return;
    HandshakeIntent intent = switch (event.intent()) {
      case STATUS -> HandshakeIntent.STATUS;
      case LOGIN -> HandshakeIntent.LOGIN;
      case TRANSFER -> HandshakeIntent.TRANSFER;
    };
    environment.events.fire(new com.velocitypowered.api.event.connection.ConnectionHandshakeEvent(new HandshakeConnection(event, intent), intent));
  }
  /** A connection that has sent its handshake and nothing else yet. A class, not a record, as PingConnection. */
  private static final class HandshakeConnection implements InboundConnection {
    private final gg.tame.conduit.api.event.proxy.ConnectionHandshakeEvent handshake;
    private final HandshakeIntent intent;
    HandshakeConnection(gg.tame.conduit.api.event.proxy.ConnectionHandshakeEvent handshake, HandshakeIntent intent) {
      this.handshake = handshake; this.intent = intent;
    }
    @Override public InetSocketAddress getRemoteAddress() { return handshake.remoteAddress(); }
    @Override public Optional<InetSocketAddress> getVirtualHost() { return Optional.of(handshake.virtualHost()); }
    @Override public Optional<String> getRawVirtualHost() { return Optional.of(handshake.virtualHost().getHostString()); }
    @Override public boolean isActive() { return true; }
    @Override public ProtocolVersion getProtocolVersion() { return ProtocolVersion.getProtocolVersion(handshake.protocolVersion()); }
    @Override public ProtocolState getProtocolState() { return ProtocolState.HANDSHAKE; }
    @Override public HandshakeIntent getHandshakeIntent() { return intent; }
  }

  // Channels and finished commands: told, not asked, as Velocity does not wait for these either.
  @Subscribe public void onChannelRegister(gg.tame.conduit.api.event.player.PlayerChannelRegisterEvent event) {
    if (listening(com.velocitypowered.api.event.player.PlayerChannelRegisterEvent.class)) {
      environment.events.fire(new com.velocitypowered.api.event.player.PlayerChannelRegisterEvent(environment.player(event.player()), identifiers(event.channels())));
    }
  }
  @Subscribe public void onChannelUnregister(gg.tame.conduit.api.event.player.PlayerChannelUnregisterEvent event) {
    if (listening(com.velocitypowered.api.event.player.PlayerChannelUnregisterEvent.class)) {
      environment.events.fire(new com.velocitypowered.api.event.player.PlayerChannelUnregisterEvent(environment.player(event.player()), identifiers(event.channels())));
    }
  }
  /** A namespaced name as Velocity's Minecraft identifier, any other as a legacy one; a name neither takes is left out. */
  private static java.util.List<ChannelIdentifier> identifiers(java.util.List<String> names) {
    java.util.List<ChannelIdentifier> identifiers = new java.util.ArrayList<>(names.size());
    for (String name : names) {
      try {
        identifiers.add(name.indexOf(':') >= 0
            ? com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier.from(name)
            : new com.velocitypowered.api.proxy.messages.LegacyChannelIdentifier(name));
      } catch (IllegalArgumentException invalid) { }
    }
    return identifiers;
  }
  /**
   * A Velocity plugin's own command is reported by VelocityCommandHost once its body has run, which is
   * usually after Conduit's handler returned; only its forwarding to the backend is reported from here.
   */
  @Subscribe public void onPostCommand(gg.tame.conduit.api.event.command.PostCommandEvent event) {
    if (!listening(com.velocitypowered.api.event.command.PostCommandInvocationEvent.class)) return;
    boolean forwarded = event.result() == gg.tame.conduit.api.event.command.PostCommandEvent.Result.FORWARDED;
    if (!forwarded && environment.commands.owns(event.command())) return;
    com.velocitypowered.api.command.CommandSource source = event.source() instanceof gg.tame.conduit.api.player.Player player
        ? environment.player(player) : environment.console;
    environment.events.fire(new com.velocitypowered.api.event.command.PostCommandInvocationEvent(source, event.command(),
        com.velocitypowered.api.command.CommandResult.valueOf(event.result().name())));
  }

  @Subscribe public void onChat(gg.tame.conduit.api.event.player.PlayerChatEvent event) {
    if (event.cancelled() || !listening(PlayerChatEvent.class)) return;
    PlayerChatEvent chat = environment.fireAndWait(new PlayerChatEvent(environment.player(event.player()), event.message()));
    if (!chat.getResult().isAllowed()) event.setCancelled(true);
    else chat.getResult().getMessage().ifPresent(message -> {
      try { event.setMessage(message); }
      catch (IllegalArgumentException refused) {
        environment.log.warning("A Velocity plugin rewrote a chat message into a command or nothing; it was sent unchanged");
      }
    });
  }
  @Subscribe public void onCommand(gg.tame.conduit.api.event.command.CommandExecuteEvent event) {
    if (event.cancelled() || !listening(CommandExecuteEvent.class)) return;
    var source = event.source() instanceof gg.tame.conduit.api.player.Player player ? environment.player(player) : environment.console;
    CommandExecuteEvent command = environment.fireAndWait(new CommandExecuteEvent(source, event.command()));
    CommandExecuteEvent.CommandResult result = command.getResult();
    // A forward is not "allowed" in Velocity's terms (the proxy is not to run it), so it is read first.
    result.getCommand().ifPresent(event::setCommand);
    if (result.isForwardToServer()) event.forwardToServer();
    else if (!result.isAllowed()) event.setCancelled(true);
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

  // Told, not asked: Velocity does not wait for these either, so neither does the thread that raised them.
  @Subscribe public void onServerRegistered(ServerRegisteredEvent event) {
    if (listening(com.velocitypowered.api.event.proxy.server.ServerRegisteredEvent.class)) {
      environment.events.fire(new com.velocitypowered.api.event.proxy.server.ServerRegisteredEvent(environment.server(event.server())));
    }
  }
  @Subscribe public void onServerUnregistered(ServerUnregisteredEvent event) {
    if (listening(com.velocitypowered.api.event.proxy.server.ServerUnregisteredEvent.class)) {
      environment.events.fire(new com.velocitypowered.api.event.proxy.server.ServerUnregisteredEvent(environment.server(event.server())));
    }
  }
  @Subscribe public void onReload(gg.tame.conduit.api.event.proxy.ProxyReloadEvent event) {
    if (listening(ProxyReloadEvent.class)) environment.events.fire(new ProxyReloadEvent());
  }
  /** The settings the client just sent, as {@code getPlayerSettings} has them. */
  @Subscribe public void onSettings(gg.tame.conduit.api.event.player.PlayerSettingsChangedEvent event) {
    if (!listening(PlayerSettingsChangedEvent.class)) return;
    VelocityPlayer player = environment.player(event.player());
    environment.events.fire(new PlayerSettingsChangedEvent(player, player.getPlayerSettings()));
  }
  @Subscribe public void onBrand(gg.tame.conduit.api.event.player.PlayerClientBrandEvent event) {
    if (listening(PlayerClientBrandEvent.class)) environment.events.fire(new PlayerClientBrandEvent(environment.player(event.player()), event.brand()));
  }
  /**
   * Told, not asked: the answer is to a plugin's own requestCookie, which the backend never made, so it
   * ends at the proxy whatever the result says. A backend's cookie traffic does not raise it.
   */
  @Subscribe public void onCookie(gg.tame.conduit.api.event.player.PlayerCookieReceiveEvent event) {
    if (!listening(com.velocitypowered.api.event.player.CookieReceiveEvent.class)) return;
    environment.events.fire(new com.velocitypowered.api.event.player.CookieReceiveEvent(environment.player(event.player()),
        net.kyori.adventure.key.Key.key(event.key()), event.data()));
  }
  /**
   * The plugins get a copy and their list is read back once they are done. One still running when the
   * wait runs out could be changing it as it is read, so the client then gets the suggestions as they
   * were.
   */
  @Subscribe public void onTabComplete(gg.tame.conduit.api.event.player.PlayerTabCompleteEvent event) {
    if (!listening(TabCompleteEvent.class)) return;
    TabCompleteEvent tab = new TabCompleteEvent(environment.player(event.player()), event.partialMessage(), new java.util.ArrayList<>(event.suggestions()));
    if (!environment.await(environment.events.fire(tab), "TabCompleteEvent")) return;
    java.util.List<String> suggestions = new java.util.ArrayList<>(tab.getSuggestions());
    event.suggestions().clear();
    event.suggestions().addAll(suggestions);
  }
  /**
   * Waited for, as Velocity waits, but for at most {@link VelocityEnvironment#WAIT_MS}: the shutdown
   * thread goes on without a handler that never finishes. The listener has already stopped accepting
   * players, so ListenerCloseEvent comes just after that rather than just before it.
   */
  @Subscribe public void onPreShutdown(gg.tame.conduit.api.event.proxy.ProxyPreShutdownEvent event) {
    if (listening(ListenerCloseEvent.class)) environment.events.fire(new ListenerCloseEvent(environment.conduit.boundAddress(), ListenerType.MINECRAFT));
    if (listening(ProxyPreShutdownEvent.class)) environment.fireAndWait(new ProxyPreShutdownEvent());
  }
  /**
   * Told, not asked: Conduit never kicks over a declined pack, so there is no kick for
   * {@code setOverwriteKick} to overrule. An Adventure callback the pack was sent with hears it too.
   */
  @Subscribe public void onResourcePack(gg.tame.conduit.api.event.player.PlayerResourcePackStatusEvent event) {
    VelocityPlayer player = environment.player(event.player());
    player.resourcePackAnswered(event.pack().id(), event.status());
    if (!listening(com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent.class)) return;
    var status = event.status() == gg.tame.conduit.api.player.ResourcePack.Status.LOADED
        ? com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent.Status.SUCCESSFUL
        : com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent.Status.valueOf(event.status().name());
    environment.events.fire(new com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent(player, event.pack().id(), status,
        VelocityResourcePackInfo.of(event.pack(), event.fromServer())));
  }
}
