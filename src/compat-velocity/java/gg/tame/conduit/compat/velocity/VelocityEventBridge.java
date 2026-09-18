package gg.tame.conduit.compat.velocity;

import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import gg.tame.conduit.api.event.Subscribe;
import gg.tame.conduit.api.event.player.PlayerDisconnectEvent;
import gg.tame.conduit.api.event.player.PlayerInitialServerEvent;
import gg.tame.conduit.api.event.player.PlayerLoginEvent;
import gg.tame.conduit.api.event.player.PlayerPostLoginEvent;
import gg.tame.conduit.api.event.player.PlayerServerConnectEvent;
import gg.tame.conduit.api.event.player.PlayerServerConnectedEvent;
import gg.tame.conduit.api.event.player.PlayerServerSwitchEvent;
import gg.tame.conduit.api.event.proxy.ProxyStartEvent;

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

  @Subscribe public void onLogin(PlayerLoginEvent event) {
    if (!listening(LoginEvent.class) || !event.allowed()) return;
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
