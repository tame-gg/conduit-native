package gg.tame.conduit.compat.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.PluginMessageEncoder;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.util.Optional;

final class VelocityServerConnection implements ServerConnection {
  private final VelocityRegisteredServer server;
  private final Player player;
  VelocityServerConnection(VelocityRegisteredServer server, Player player) {
    this.server = server;
    this.player = player;
  }
  @Override public RegisteredServer getServer() { return server; }
  @Override public Optional<RegisteredServer> getPreviousServer() { return Optional.empty(); }
  @Override public ServerInfo getServerInfo() { return server.getServerInfo(); }
  @Override public Player getPlayer() { return player; }
  @Override public boolean sendPluginMessage(ChannelIdentifier identifier, byte[] data) { return false; }
  @Override public boolean sendPluginMessage(ChannelIdentifier identifier, PluginMessageEncoder encoder) { return false; }
}
