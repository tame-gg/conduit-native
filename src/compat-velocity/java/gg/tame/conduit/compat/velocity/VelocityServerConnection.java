package gg.tame.conduit.compat.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.PluginMessageEncoder;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.util.Optional;

/** The player's link to the backend they are on. Plugin messages go to that backend. */
final class VelocityServerConnection implements ServerConnection {
  private final VelocityRegisteredServer server;
  private final VelocityPlayer player;
  private final VelocityRegisteredServer previous;
  VelocityServerConnection(VelocityRegisteredServer server, VelocityPlayer player, VelocityRegisteredServer previous) {
    this.server = server; this.player = player; this.previous = previous;
  }
  @Override public RegisteredServer getServer() { return server; }
  @Override public Optional<RegisteredServer> getPreviousServer() { return Optional.ofNullable(previous); }
  @Override public ServerInfo getServerInfo() { return server.getServerInfo(); }
  @Override public Player getPlayer() { return player; }
  /** False once the player has moved on from this server, or when the backend cannot take one now. */
  @Override public boolean sendPluginMessage(ChannelIdentifier identifier, byte[] data) {
    var current = player.nativePlayer().currentServer();
    if (!current.isPresent() || !current.name().equalsIgnoreCase(server.getServerInfo().getName())) return false;
    return player.nativePlayer().sendPluginMessageToServer(identifier.getId(), data.clone());
  }
  @Override public boolean sendPluginMessage(ChannelIdentifier identifier, PluginMessageEncoder encoder) {
    return sendPluginMessage(identifier, VelocityPlayer.encode(encoder));
  }
  @Override public boolean equals(Object other) {
    return other instanceof VelocityServerConnection that && that.player.equals(player) && that.server.equals(server);
  }
  @Override public int hashCode() { return 31 * player.hashCode() + server.hashCode(); }
}
