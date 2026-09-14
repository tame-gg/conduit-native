package gg.tame.conduit.compat.velocity;

import com.velocitypowered.api.network.HandshakeIntent;
import com.velocitypowered.api.network.ProtocolState;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.crypto.IdentifiedKey;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.PluginMessageEncoder;
import com.velocitypowered.api.proxy.player.PlayerSettings;
import com.velocitypowered.api.proxy.player.ResourcePackInfo;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.util.GameProfile;
import com.velocitypowered.api.util.ModInfo;
import com.velocitypowered.api.util.ServerLink;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;

final class VelocityPlayer implements Player {
  private final VelocityEnvironment environment;
  private final gg.tame.conduit.api.player.Player nativePlayer;
  VelocityPlayer(VelocityEnvironment environment, gg.tame.conduit.api.player.Player nativePlayer) {
    this.environment = environment;
    this.nativePlayer = nativePlayer;
  }
  gg.tame.conduit.api.player.Player nativePlayer() { return nativePlayer; }
  @Override public String getUsername() { return nativePlayer.username(); }
  @Override public UUID getUniqueId() { return nativePlayer.uniqueId(); }
  @Override public boolean isOnlineMode() { return nativePlayer.authenticated(); }
  @Override public Tristate getPermissionValue(String permission) {
    return nativePlayer.hasPermission(permission) ? Tristate.TRUE : Tristate.FALSE;
  }
  @Override public void sendMessage(Component message) { nativePlayer.sendMessage(Texts.plain(message)); }
  @Override public void disconnect(Component reason) { nativePlayer.disconnect(Texts.plain(reason)); }
  @Override public Optional<ServerConnection> getCurrentServer() {
    var current = nativePlayer.currentServer();
    if (!current.isPresent()) return Optional.empty();
    return Optional.of(new VelocityServerConnection(environment.wrapServer(current.orElse(null)), this));
  }
  @Override public ConnectionRequestBuilder createConnectionRequest(RegisteredServer server) {
    return new VelocityConnectionRequest(this, server);
  }
  @Override public boolean sendPluginMessage(ChannelIdentifier identifier, byte[] data) {
    nativePlayer.sendPluginMessage(identifier.getId(), data);
    return true;
  }
  @Override public boolean sendPluginMessage(ChannelIdentifier identifier, PluginMessageEncoder encoder) {
    return UnsupportedApis.unsupported("Player.sendPluginMessage(encoder)");
  }
  @Override public Identity identity() { return Identity.identity(nativePlayer.uniqueId()); }
  @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("0.0.0.0", 0); }
  @Override public Optional<InetSocketAddress> getVirtualHost() { return Optional.empty(); }
  @Override public Optional<String> getRawVirtualHost() { return Optional.empty(); }
  @Override public boolean isActive() { return true; }
  @Override public ProtocolVersion getProtocolVersion() { return ProtocolVersion.UNKNOWN; }
  @Override public ProtocolState getProtocolState() {
    try { return ProtocolState.valueOf(nativePlayer.connectionState()); }
    catch (IllegalArgumentException ignored) { return ProtocolState.PLAY; }
  }
  @Override public HandshakeIntent getHandshakeIntent() { return HandshakeIntent.LOGIN; }
  @Override public Locale getEffectiveLocale() { return Locale.US; }
  @Override public void setEffectiveLocale(Locale locale) { }
  @Override public PlayerSettings getPlayerSettings() { return UnsupportedApis.unsupported("Player.getPlayerSettings"); }
  @Override public boolean hasSentPlayerSettings() { return false; }
  @Override public Optional<ModInfo> getModInfo() { return Optional.empty(); }
  @Override public long getPing() { return -1; }
  @Override public List<GameProfile.Property> getGameProfileProperties() { return List.of(); }
  @Override public void setGameProfileProperties(List<GameProfile.Property> properties) { }
  @Override public GameProfile getGameProfile() { return new GameProfile(getUniqueId(), getUsername(), List.of()); }
  @Override public void clearPlayerListHeaderAndFooter() { }
  @Override public Component getPlayerListHeader() { return Component.empty(); }
  @Override public Component getPlayerListFooter() { return Component.empty(); }
  @Override public TabList getTabList() { return UnsupportedApis.unsupported("Player.getTabList"); }
  @Override public void spoofChatInput(String input) { }
  @Override public void sendResourcePack(String url) { UnsupportedApis.unsupported("Player.sendResourcePack"); }
  @Override public void sendResourcePack(String url, byte[] hash) { UnsupportedApis.unsupported("Player.sendResourcePack"); }
  @Override public void sendResourcePackOffer(ResourcePackInfo pack) { UnsupportedApis.unsupported("Player.sendResourcePackOffer"); }
  @Override public ResourcePackInfo getAppliedResourcePack() { return null; }
  @Override public ResourcePackInfo getPendingResourcePack() { return null; }
  @Override public Collection<ResourcePackInfo> getAppliedResourcePacks() { return List.of(); }
  @Override public Collection<ResourcePackInfo> getPendingResourcePacks() { return List.of(); }
  @Override public String getClientBrand() { return "Conduit"; }
  @Override public void addCustomChatCompletions(Collection<String> completions) { }
  @Override public void removeCustomChatCompletions(Collection<String> completions) { }
  @Override public void setCustomChatCompletions(Collection<String> completions) { }
  @Override public void transferToHost(InetSocketAddress address) { UnsupportedApis.unsupported("Player.transferToHost"); }
  @Override public void storeCookie(Key key, byte[] data) { UnsupportedApis.unsupported("Player.storeCookie"); }
  @Override public void requestCookie(Key key) { UnsupportedApis.unsupported("Player.requestCookie"); }
  @Override public void setServerLinks(List<ServerLink> links) { }
  @Override public IdentifiedKey getIdentifiedKey() { return null; }
  @Override public HoverEvent<HoverEvent.ShowEntity> asHoverEvent(java.util.function.UnaryOperator<HoverEvent.ShowEntity> op) {
    return Player.super.asHoverEvent(op);
  }
}
