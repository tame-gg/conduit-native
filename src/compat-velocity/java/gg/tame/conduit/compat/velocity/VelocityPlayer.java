// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.compat.velocity;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
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
import com.velocitypowered.api.proxy.player.SkinParts;
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
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.dialog.DialogLike;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.TitlePart;

/** A Conduit player as a Velocity Player. What Conduit does not track throws rather than guesses. */
final class VelocityPlayer implements Player, Unsupported.ChatOnly {
  private final VelocityEnvironment environment;
  private final gg.tame.conduit.api.player.Player player;
  /** The server before the current one, from the last switch Conduit reported. */
  volatile VelocityRegisteredServer previousServer;
  /** A plugin's setEffectiveLocale; null leaves the client's own. */
  private volatile Locale effectiveLocale;

  VelocityPlayer(VelocityEnvironment environment, gg.tame.conduit.api.player.Player player) {
    this.environment = environment;
    this.player = player;
  }
  gg.tame.conduit.api.player.Player nativePlayer() { return player; }

  @Override public String getUsername() { return player.username(); }
  @Override public UUID getUniqueId() { return player.uniqueId(); }
  @Override public Identity identity() { return Identity.identity(player.uniqueId()); }
  @Override public boolean isOnlineMode() { return player.authenticated(); }
  /** The function PermissionsSetupEvent gave this player, else Conduit's own yes or no. */
  @Override public Tristate getPermissionValue(String permission) {
    var function = environment.permissions.function(player);
    return function != null ? function.getPermissionValue(permission) : Tristate.fromBoolean(player.hasPermission(permission));
  }
  @Override public void deliver(Component message) { player.sendMessage(Texts.toConduit(message)); }
  @Override public void disconnect(Component reason) { player.disconnect(Texts.toConduit(reason)); }

  /** Conduit knows the address, not the port, when the address is forwarded; the port is then 0. */
  @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress(player.remoteAddress(), 0); }
  @Override public Optional<InetSocketAddress> getVirtualHost() { return Optional.ofNullable(player.virtualHost()); }
  @Override public Optional<String> getRawVirtualHost() { return getVirtualHost().map(InetSocketAddress::getHostString); }
  @Override public ProtocolVersion getProtocolVersion() { return ProtocolVersion.getProtocolVersion(player.protocolVersion()); }
  @Override public boolean isActive() { return environment.conduit.player(player.uniqueId()).filter(live -> live == player).isPresent(); }
  @Override public ProtocolState getProtocolState() {
    try { return ProtocolState.valueOf(player.connectionState()); }
    catch (IllegalArgumentException unknown) { throw Unsupported.api("Player.getProtocolState for state " + player.connectionState()); }
  }

  @Override public Optional<ServerConnection> getCurrentServer() {
    if (!player.currentServer().isPresent()) return Optional.empty();
    VelocityRegisteredServer current = environment.server(player.currentServer().orElse(null));
    return current == null ? Optional.empty() : Optional.of(new VelocityServerConnection(current, this, previousServer));
  }
  @Override public ConnectionRequestBuilder createConnectionRequest(RegisteredServer server) {
    return new VelocityConnectionRequest(environment, this, server);
  }
  @Override public boolean sendPluginMessage(ChannelIdentifier identifier, byte[] data) {
    player.sendPluginMessage(identifier.getId(), data.clone());
    return true;
  }
  @Override public boolean sendPluginMessage(ChannelIdentifier identifier, PluginMessageEncoder encoder) {
    return sendPluginMessage(identifier, encode(encoder));
  }
  static byte[] encode(PluginMessageEncoder encoder) {
    ByteArrayDataOutput output = ByteStreams.newDataOutput();
    encoder.encode(output);
    return output.toByteArray();
  }
  @Override public GameProfile getGameProfile() { return new GameProfile(getUniqueId(), getUsername(), List.of()); }
  @Override public long getPing() { return player.ping(); }

  // Not tracked by Conduit's API.
  @Override public HandshakeIntent getHandshakeIntent() { throw Unsupported.api("Player.getHandshakeIntent"); }
  /** What a plugin set, or else the client's own language; null until the client has sent its settings. */
  @Override public Locale getEffectiveLocale() { return effectiveLocale != null ? effectiveLocale : player.locale().orElse(null); }
  @Override public void setEffectiveLocale(Locale locale) { effectiveLocale = locale; }
  /**
   * The client's language once it has sent its settings. Conduit's API carries nothing else from them,
   * so the rest are the vanilla client's defaults.
   */
  @Override public PlayerSettings getPlayerSettings() { return new Settings(player.locale().orElse(Locale.US)); }
  @Override public boolean hasSentPlayerSettings() { return player.locale().isPresent(); }
  @Override public Optional<ModInfo> getModInfo() { throw Unsupported.api("Player.getModInfo"); }
  @Override public String getClientBrand() { throw Unsupported.api("Player.getClientBrand"); }
  @Override public IdentifiedKey getIdentifiedKey() { throw Unsupported.api("Player.getIdentifiedKey"); }
  @Override public List<GameProfile.Property> getGameProfileProperties() { throw Unsupported.api("Player.getGameProfileProperties"); }
  @Override public void setGameProfileProperties(List<GameProfile.Property> properties) { throw Unsupported.api("Player.setGameProfileProperties"); }
  // Titles, action bar, boss bars and the tab-list header, through Conduit's own display.
  @Override public void sendActionBar(Component message) { player.sendActionBar(Texts.toConduit(message)); }
  @Override public void showTitle(Title title) {
    player.showTitle(Texts.toConduit(title.title()), Texts.toConduit(title.subtitle()), title.times() == null ? null : times(title.times()));
  }
  @Override public <T> void sendTitlePart(TitlePart<T> part, T value) {
    if (part == TitlePart.TITLE) player.sendTitle(Texts.toConduit((Component) value));
    else if (part == TitlePart.SUBTITLE) player.sendSubtitle(Texts.toConduit((Component) value));
    else if (part == TitlePart.TIMES) player.sendTitleTimes(times((Title.Times) value));
    else throw new IllegalArgumentException("Unknown TitlePart '" + part + "'");
  }
  /** The client counts title times in ticks, so a duration is rounded down to a whole one. */
  private static gg.tame.conduit.api.player.TitleTimes times(Title.Times times) {
    return new gg.tame.conduit.api.player.TitleTimes(ticks(times.fadeIn()), ticks(times.stay()), ticks(times.fadeOut()));
  }
  private static int ticks(java.time.Duration duration) { return (int) Math.min(Integer.MAX_VALUE, duration.toMillis() / 50); }
  @Override public void clearTitle() { player.clearTitle(); }
  @Override public void resetTitle() { player.resetTitle(); }
  @Override public void showBossBar(BossBar bar) { environment.bossBars.show(player, bar); }
  @Override public void hideBossBar(BossBar bar) { environment.bossBars.hide(player, bar); }
  @Override public void sendPlayerListHeaderAndFooter(Component header, Component footer) {
    player.sendPlayerListHeaderAndFooter(Texts.toConduit(header), Texts.toConduit(footer));
  }
  @Override public void sendPlayerListHeader(Component header) {
    player.sendPlayerListHeaderAndFooter(Texts.toConduit(header), player.playerListFooter());
  }
  @Override public void sendPlayerListFooter(Component footer) {
    player.sendPlayerListHeaderAndFooter(player.playerListHeader(), Texts.toConduit(footer));
  }
  @Override public void clearPlayerListHeaderAndFooter() {
    player.sendPlayerListHeaderAndFooter(gg.tame.conduit.api.text.Text.empty(), gg.tame.conduit.api.text.Text.empty());
  }
  /** What a plugin set through the proxy; a header the backend sent is not seen. */
  @Override public Component getPlayerListHeader() { return Texts.toAdventure(player.playerListHeader()); }
  @Override public Component getPlayerListFooter() { return Texts.toAdventure(player.playerListFooter()); }
  @Override public TabList getTabList() { return new VelocityTabList(this); }
  @Override public void spoofChatInput(String input) { throw Unsupported.api("Player.spoofChatInput"); }
  @Override public void sendResourcePack(String url) { throw Unsupported.api("Player.sendResourcePack"); }
  @Override public void sendResourcePack(String url, byte[] hash) { throw Unsupported.api("Player.sendResourcePack"); }
  @Override public void sendResourcePackOffer(ResourcePackInfo pack) { throw Unsupported.api("Player.sendResourcePackOffer"); }
  @Override public ResourcePackInfo getAppliedResourcePack() { throw Unsupported.api("Player.getAppliedResourcePack"); }
  @Override public ResourcePackInfo getPendingResourcePack() { throw Unsupported.api("Player.getPendingResourcePack"); }
  @Override public Collection<ResourcePackInfo> getAppliedResourcePacks() { throw Unsupported.api("Player.getAppliedResourcePacks"); }
  @Override public Collection<ResourcePackInfo> getPendingResourcePacks() { throw Unsupported.api("Player.getPendingResourcePacks"); }
  @Override public void addCustomChatCompletions(Collection<String> completions) { throw Unsupported.api("Player.addCustomChatCompletions"); }
  @Override public void removeCustomChatCompletions(Collection<String> completions) { throw Unsupported.api("Player.removeCustomChatCompletions"); }
  @Override public void setCustomChatCompletions(Collection<String> completions) { throw Unsupported.api("Player.setCustomChatCompletions"); }
  @Override public void transferToHost(InetSocketAddress address) { throw Unsupported.api("Player.transferToHost"); }
  @Override public void storeCookie(Key key, byte[] data) { throw Unsupported.api("Player.storeCookie"); }
  @Override public void requestCookie(Key key) { throw Unsupported.api("Player.requestCookie"); }
  @Override public void setServerLinks(List<ServerLink> links) { throw Unsupported.api("Player.setServerLinks"); }
  // Player and Unsupported.ChatOnly both default these; the class has to pick.
  @Override public void playSound(Sound sound) { throw Unsupported.api("Player.playSound"); }
  @Override public void playSound(Sound sound, double x, double y, double z) { throw Unsupported.api("Player.playSound"); }
  @Override public void playSound(Sound sound, Sound.Emitter emitter) { throw Unsupported.api("Player.playSound"); }
  @Override public void stopSound(SoundStop stop) { throw Unsupported.api("Player.stopSound"); }
  @Override public void openBook(Book book) { throw Unsupported.api("Player.openBook"); }
  @Override public void showDialog(DialogLike dialog) { throw Unsupported.api("Player.showDialog"); }
  @Override public void closeDialog() { throw Unsupported.api("Player.closeDialog"); }

  private record Settings(Locale getLocale) implements PlayerSettings {
    @Override public byte getViewDistance() { return 12; }
    @Override public ChatMode getChatMode() { return ChatMode.SHOWN; }
    @Override public boolean hasChatColors() { return true; }
    @Override public SkinParts getSkinParts() { return new SkinParts((byte) 0x7F); }
    @Override public MainHand getMainHand() { return MainHand.RIGHT; }
    @Override public boolean isClientListingAllowed() { return true; }
    @Override public boolean isTextFilteringEnabled() { return false; }
    @Override public ParticleStatus getParticleStatus() { return ParticleStatus.ALL; }
  }

  @Override public boolean equals(Object other) { return other instanceof VelocityPlayer that && that.player == player; }
  @Override public int hashCode() { return System.identityHashCode(player); }
  @Override public String toString() { return "VelocityPlayer[" + getUsername() + "]"; }
}
