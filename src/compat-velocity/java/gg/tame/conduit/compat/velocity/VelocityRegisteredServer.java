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

final class VelocityRegisteredServer implements RegisteredServer {
  private final VelocityEnvironment environment;
  private final gg.tame.conduit.api.server.RegisteredServer nativeServer;
  private final ServerInfo info;
  VelocityRegisteredServer(VelocityEnvironment environment, gg.tame.conduit.api.server.RegisteredServer nativeServer) {
    this.environment = environment;
    this.nativeServer = nativeServer;
    this.info = new ServerInfo(nativeServer.getName(), nativeServer.getAddress());
  }
  gg.tame.conduit.api.server.RegisteredServer nativeServer() { return nativeServer; }
  @Override public ServerInfo getServerInfo() { return info; }
  @Override public Collection<Player> getPlayersConnected() {
    List<Player> players = new ArrayList<>();
    for (var player : environment.runtime().players().all()) {
      if (nativeServer.getName().equalsIgnoreCase(player.currentServer().name())) players.add(environment.wrap(player));
    }
    return players;
  }
  @Override public CompletableFuture<ServerPing> ping() { return UnsupportedApis.unsupported("RegisteredServer.ping"); }
  @Override public CompletableFuture<ServerPing> ping(PingOptions pingOptions) { return ping(); }
  @Override public boolean sendPluginMessage(ChannelIdentifier identifier, byte[] data) { return false; }
  @Override public boolean sendPluginMessage(ChannelIdentifier identifier, PluginMessageEncoder encoder) { return false; }
  @Override public void sendMessage(Component message) {
    for (Player player : getPlayersConnected()) player.sendMessage(message);
  }
}
