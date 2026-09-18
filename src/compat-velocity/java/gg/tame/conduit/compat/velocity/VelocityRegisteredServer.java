package gg.tame.conduit.compat.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.PluginMessageEncoder;
import com.velocitypowered.api.proxy.server.PingOptions;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.proxy.server.ServerPing;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
  @Override public CompletableFuture<ServerPing> ping() { throw Unsupported.api("RegisteredServer.ping"); }
  @Override public CompletableFuture<ServerPing> ping(PingOptions options) { throw Unsupported.api("RegisteredServer.ping"); }
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
