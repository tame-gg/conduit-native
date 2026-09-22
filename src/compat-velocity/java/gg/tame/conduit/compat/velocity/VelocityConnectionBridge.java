// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.compat.velocity;

import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent.PreLoginComponentResult;
import com.velocitypowered.api.event.connection.PreTransferEvent;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.network.HandshakeIntent;
import com.velocitypowered.api.network.ProtocolState;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.LoginPhaseConnection;
import com.velocitypowered.api.proxy.crypto.IdentifiedKey;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import gg.tame.conduit.api.event.Subscribe;
import gg.tame.conduit.api.event.player.PlayerPreLoginEvent;
import gg.tame.conduit.api.event.player.PlayerPreLoginEvent.Authentication;
import gg.tame.conduit.api.event.player.PlayerTransferEvent;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;
import net.kyori.adventure.text.Component;

/**
 * The events about where a connection may come from and go: PreLoginEvent and GameProfileRequestEvent
 * for a connection that is not a player yet, and PreTransferEvent. Like the other bridge, the native
 * thread waits for Velocity's handlers, and what they leave in the Velocity event is written back to
 * the native one.
 */
final class VelocityConnectionBridge {
  private final VelocityEnvironment environment;
  VelocityConnectionBridge(VelocityEnvironment environment) { this.environment = environment; }

  /**
   * A transfer, whoever asked for it -- a plugin's transferToHost or the backend -- as Velocity fires
   * it. A denied result cancels it; one that names an address sends the player there instead.
   */
  @Subscribe public void onTransfer(PlayerTransferEvent event) {
    if (event.cancelled() || !environment.events.listening(PreTransferEvent.class)) return;
    PreTransferEvent transfer = environment.fireAndWait(new PreTransferEvent(environment.player(event.player()),
        InetSocketAddress.createUnresolved(event.host(), event.port())));
    PreTransferEvent.TransferResult result = transfer.getResult();
    if (!result.isAllowed()) {
      event.setCancelled(true);
      return;
    }
    InetSocketAddress chosen = result.address();
    if (chosen == null) return;
    try {
      event.setTarget(chosen.getHostString(), chosen.getPort());
    } catch (IllegalArgumentException unusable) {
      environment.log.warning("A Velocity plugin's transfer address for " + event.player().username() + " was not used: " + unusable.getMessage());
    }
  }

  /**
   * Velocity's four results onto Conduit's deny and authentication choice. Left as it was offered,
   * whatever native listeners decided before this stands.
   */
  @Subscribe public void onPreLogin(PlayerPreLoginEvent event) {
    if (!environment.events.listening(PreLoginEvent.class)) return;
    PreLoginComponentResult offered = !event.allowed()
        ? PreLoginComponentResult.denied(Texts.toAdventure(event.denyReason().orElseThrow()))
        : switch (event.authentication()) {
          case FORCE_ONLINE -> PreLoginComponentResult.forceOnlineMode();
          case FORCE_OFFLINE -> PreLoginComponentResult.forceOfflineMode();
          case PROXY_DEFAULT -> PreLoginComponentResult.allowed();
        };
    PreLoginEvent pre = new PreLoginEvent(new LoginConnection(environment, event.remoteAddress(), event.virtualHost(), event.protocolVersion(),
        event.transferred(), event::sendLoginPluginMessage), event.username(), event.claimedUniqueId().orElse(null));
    pre.setResult(offered);
    // A login a plugin has not finished deciding on is refused, not let in. The result it was
    // offered is "allowed", so carrying on with it when a ban plugin's database was slow to answer
    // admitted exactly the players that plugin was there to keep out.
    if (!environment.await(environment.events.fire(pre), "PreLoginEvent")) {
      event.deny(Texts.toConduit(VelocityEnvironment.LOGIN_UNDECIDED));
      return;
    }
    PreLoginComponentResult result = pre.getResult();
    if (result == offered) return;
    if (!result.isAllowed()) {
      event.deny(Texts.toConduit(result.getReasonComponent().orElse(Component.empty())));
      return;
    }
    event.allow();
    event.setAuthentication(result.isForceOfflineMode() ? Authentication.FORCE_OFFLINE
        : result.isOnlineModeAllowed() ? Authentication.FORCE_ONLINE : Authentication.PROXY_DEFAULT);
  }

  /**
   * The profile a Velocity plugin leaves, if it is not the one offered, replaces the native one. A
   * name Conduit would not let a player log in with is refused with a warning, and the profile stays.
   */
  @Subscribe public void onGameProfileRequest(gg.tame.conduit.api.event.player.GameProfileRequestEvent event) {
    if (!environment.events.listening(GameProfileRequestEvent.class)) return;
    com.velocitypowered.api.util.GameProfile offered = Profiles.toVelocity(event.gameProfile());
    GameProfileRequestEvent request = new GameProfileRequestEvent(new LoginConnection(environment, event.remoteAddress(), event.virtualHost(),
        event.protocolVersion(), event.transferred(), event::sendLoginPluginMessage), Profiles.toVelocity(event.originalProfile()), event.onlineMode());
    if (!event.gameProfile().equals(event.originalProfile())) request.setGameProfile(offered);
    com.velocitypowered.api.util.GameProfile chosen = environment.fireAndWait(request).getGameProfile();
    if (chosen == request.getOriginalProfile() || chosen == offered) return;
    try {
      event.setGameProfile(Profiles.toConduit(chosen));
    } catch (RuntimeException unusable) {
      environment.log.warning("A Velocity plugin's game profile for " + event.username() + " was not used: " + unusable.getMessage());
    }
  }

  /**
   * A backend's login query that Conduit has no answer of its own for. The native thread waits; a
   * reply from a plugin is the backend's answer, and {@code unknown()}, the default, leaves the query as
   * Conduit handles it without plugins: relayed to a client still logging in, or failing a switch.
   */
  @Subscribe public void onBackendLoginMessage(gg.tame.conduit.api.event.player.BackendLoginPluginMessageEvent event) {
    if (!environment.events.listening(com.velocitypowered.api.event.player.ServerLoginPluginMessageEvent.class)) return;
    VelocityRegisteredServer server = environment.server(event.server());
    if (server == null) return;
    VelocityPlayer player = environment.player(event.player());
    VelocityRegisteredServer current = (VelocityRegisteredServer) player.getCurrentServer()
        .map(com.velocitypowered.api.proxy.ServerConnection::getServer).orElse(null);
    ChannelIdentifier channel;
    try { channel = com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier.from(event.channel()); }
    catch (IllegalArgumentException notNamespaced) { channel = new com.velocitypowered.api.proxy.messages.LegacyChannelIdentifier(event.channel()); }
    var message = environment.fireAndWait(new com.velocitypowered.api.event.player.ServerLoginPluginMessageEvent(
        new VelocityServerConnection(server, player, current), channel, event.data(), event.messageId()));
    if (message.getResult().isAllowed()) event.reply(message.getResult().getResponse());
  }

  /**
   * A connection in Login, before it is a player. A class, not a record, so no public accessor hands
   * a plugin a Conduit type. It is a LoginPhaseConnection because Velocity documents that cast.
   */
  static final class LoginConnection implements LoginPhaseConnection {
    private final VelocityEnvironment environment;
    private final InetAddress address;
    private final InetSocketAddress virtualHost;
    private final int protocol;
    private final boolean transferred;
    private final java.util.function.BiFunction<String, byte[], java.util.concurrent.CompletableFuture<byte[]>> messages;
    LoginConnection(VelocityEnvironment environment, InetAddress address, InetSocketAddress virtualHost, int protocol, boolean transferred,
                    java.util.function.BiFunction<String, byte[], java.util.concurrent.CompletableFuture<byte[]>> messages) {
      this.environment = environment; this.address = address; this.virtualHost = virtualHost; this.protocol = protocol;
      this.transferred = transferred; this.messages = messages;
    }
    /** The port is 0, as for a player: Conduit's API carries the address only. */
    @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress(address, 0); }
    @Override public Optional<InetSocketAddress> getVirtualHost() { return Optional.ofNullable(virtualHost); }
    @Override public Optional<String> getRawVirtualHost() { return getVirtualHost().map(InetSocketAddress::getHostString); }
    @Override public boolean isActive() { return true; }
    @Override public ProtocolVersion getProtocolVersion() { return ProtocolVersion.getProtocolVersion(protocol); }
    @Override public ProtocolState getProtocolState() { return ProtocolState.LOGIN; }
    @Override public HandshakeIntent getHandshakeIntent() { return transferred ? HandshakeIntent.TRANSFER : HandshakeIntent.LOGIN; }
    /**
     * Queued at once, sent at the next point Conduit's login stops for such messages (see Conduit's
     * PlayerPreLoginEvent.sendLoginPluginMessage), and the login waits for the answer. The consumer
     * runs on the adapter's threads, with null when the client did not understand the channel; not at
     * all when the login ends first. A client before 1.13, or a login already decided, throws
     * IllegalStateException.
     */
    @Override public void sendLoginPluginMessage(ChannelIdentifier identifier, byte[] contents, MessageConsumer consumer) {
      java.util.Objects.requireNonNull(consumer, "consumer");
      messages.apply(identifier.getId(), contents).thenAcceptAsync(answer -> {
        try { consumer.onMessageResponse(answer); }
        catch (RuntimeException failed) { environment.log.log(java.util.logging.Level.WARNING, "A login plugin message consumer failed", failed); }
      }, environment.work);
    }
    /** Null, as for a connection that sent none: Conduit does not keep the client's chat key. */
    @Override public IdentifiedKey getIdentifiedKey() { return null; }
  }
}
