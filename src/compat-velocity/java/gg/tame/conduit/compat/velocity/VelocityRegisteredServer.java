// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.compat.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.PluginMessageEncoder;
import com.velocitypowered.api.proxy.server.PingOptions;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.proxy.server.ServerPing;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.kyori.adventure.text.Component;

/** A backend registered with Conduit. Equal to another wrapper of the same name and address. */
final class VelocityRegisteredServer implements RegisteredServer, Unsupported.ChatOnly {
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
  /**
   * Conduit's own status probe of the backend: its version and player counts. The probe does not
   * keep the backend's MOTD, icon or player sample, so the description is empty and there is none.
   * A backend that does not answer fails the future, and callbacks run on the adapter's threads.
   */
  @Override public CompletableFuture<ServerPing> ping() {
    return server.ping().thenApplyAsync(status -> {
      if (!status.online()) throw new CompletionException(new IOException("server " + info.getName() + " did not answer the ping"));
      return new ServerPing(new ServerPing.Version(status.protocol().orElse(-1), status.versionName()),
          new ServerPing.Players(status.onlinePlayers().orElse(0), status.maxPlayers().orElse(0), List.of()), Component.empty(), null);
    }, environment.work);
  }
  /** Conduit's probe takes no options: only the defaults can be honoured. */
  @Override public CompletableFuture<ServerPing> ping(PingOptions options) {
    if (!PingOptions.DEFAULT.equals(options)) throw Unsupported.api("RegisteredServer.ping(PingOptions) with options other than the defaults");
    return ping();
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
  @Override public boolean equals(Object other) { return other instanceof VelocityRegisteredServer that && that.info.equals(info); }
  @Override public int hashCode() { return info.hashCode(); }
  @Override public String toString() { return "VelocityRegisteredServer[" + info.getName() + "]"; }
}
