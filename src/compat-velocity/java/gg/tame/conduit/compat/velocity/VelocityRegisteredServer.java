// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.compat.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.PluginMessageEncoder;
import com.velocitypowered.api.proxy.server.PingOptions;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.api.util.Favicon;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;

/** A backend registered with Conduit. Equal to another wrapper of the same name and address. */
final class VelocityRegisteredServer implements RegisteredServer, Unsupported.PlayerGroup {
  private final VelocityEnvironment environment;
  private final gg.tame.conduit.api.server.RegisteredServer server;
  private final ServerInfo info;
  VelocityRegisteredServer(VelocityEnvironment environment, gg.tame.conduit.api.server.RegisteredServer server) {
    this.environment = environment;
    this.server = server;
    this.info = new ServerInfo(server.getName(), server.getAddress());
  }
  gg.tame.conduit.api.server.RegisteredServer nativeServer() { return server; }

  @Override public ServerInfo getServerInfo() { return info; }
  @Override public Collection<Player> getPlayersConnected() {
    List<Player> players = new ArrayList<>();
    for (var player : server.players()) players.add(environment.player(player));
    return List.copyOf(players);
  }
  @Override public CompletableFuture<ServerPing> ping() { return ping(PingOptions.DEFAULT); }
  /**
   * The backend's own status answer: version, players with their sample, description (as Conduit
   * Text carries it), favicon and a Forge backend's mod list. Every option is honoured: an unknown version or no
   * virtual host leaves the native defaults, and a zero timeout is Conduit's health-check timeout. A
   * backend that does not answer fails the future, and callbacks run on the adapter's threads.
   */
  @Override public CompletableFuture<ServerPing> ping(PingOptions options) {
    Duration timeout = options.getTimeout() > 0 ? Duration.ofMillis(options.getTimeout()) : null;
    return server.ping(options.getProtocolVersion().getProtocol(), options.getVirtualHost(), timeout).thenApplyAsync(status -> {
      if (!status.online()) throw new CompletionException(new IOException("server " + info.getName() + " did not answer the ping"));
      boolean counted = status.onlinePlayers().isPresent() || status.maxPlayers().isPresent();
      List<ServerPing.SamplePlayer> sample = status.samplePlayers().stream()
          .map(player -> new ServerPing.SamplePlayer(player.name(), player.uniqueId())).toList();
      return new ServerPing(new ServerPing.Version(status.protocol().orElse(-1), status.versionName()),
          counted ? new ServerPing.Players(status.onlinePlayers().orElse(0), status.maxPlayers().orElse(0), sample) : null,
          // Null, not the four-argument constructor's empty FML list, for a backend that sent none.
          Texts.toAdventure(status.description()), status.favicon().map(Favicon::new).orElse(null),
          status.modInfo().map(VelocityEventBridge::toVelocity).orElse(null));
    }, environment.work);
  }
  /** Through a player on the server, as there is no other connection to it; false with nobody there. */
  @Override public boolean sendPluginMessage(ChannelIdentifier identifier, byte[] data) {
    return server.sendPluginMessage(identifier.getId(), data.clone());
  }
  @Override public boolean sendPluginMessage(ChannelIdentifier identifier, PluginMessageEncoder encoder) {
    return sendPluginMessage(identifier, VelocityPlayer.encode(encoder));
  }
  @Override public void deliver(Component message) {
    for (Player player : getPlayersConnected()) player.sendMessage(message);
  }
  @Override public Iterable<? extends Audience> audiences() { return getPlayersConnected(); }
  @Override public boolean equals(Object other) { return other instanceof VelocityRegisteredServer that && that.info.equals(info); }
  @Override public int hashCode() { return info.hashCode(); }
  @Override public String toString() { return "VelocityRegisteredServer[" + info.getName() + "]"; }
}
