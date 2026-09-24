// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.compat.velocity;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.velocitypowered.api.event.player.CookieRequestEvent;
import com.velocitypowered.api.event.player.CookieStoreEvent;
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
import gg.tame.conduit.api.player.ClientSettings;
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
import net.kyori.adventure.resource.ResourcePackCallback;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.resource.ResourcePackStatus;
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
  /** Adventure callbacks for packs sent with one, until the client's last answer about each. */
  private final java.util.Map<UUID, ResourcePackCallback> packCallbacks = new java.util.concurrent.ConcurrentHashMap<>();
  /** Set by the event bridge from the client's FML|HS mod list. */
  volatile ModInfo modInfo;

  VelocityPlayer(VelocityEnvironment environment, gg.tame.conduit.api.player.Player player) {
    this.environment = environment;
    this.player = player;
  }
  gg.tame.conduit.api.player.Player nativePlayer() { return player; }

  @Override public String getUsername() { return player.username(); }
  @Override public UUID getUniqueId() { return player.uniqueId(); }
  @Override public Identity identity() { return Identity.identity(player.uniqueId()); }
  /**
   * What Adventure code asks an audience about itself: plugins find the player behind an audience
   * with {@code get(Identity.UUID)}, which is empty without this.
   */
  @Override public net.kyori.adventure.pointer.Pointers pointers() {
    return net.kyori.adventure.pointer.Pointers.builder()
        .withStatic(Identity.UUID, getUniqueId())
        .withStatic(Identity.NAME, getUsername())
        .withDynamic(Identity.LOCALE, this::getEffectiveLocale)
        .withStatic(net.kyori.adventure.permission.PermissionChecker.POINTER, getPermissionChecker())
        .build();
  }
  @Override public boolean isOnlineMode() { return player.authenticated(); }
  /** The function PermissionsSetupEvent gave this player, else Conduit's own yes or no. */
  @Override public Tristate getPermissionValue(String permission) {
    var function = environment.permissions.function(player);
    if (function != null) return function.getPermissionValue(permission);
    // Conduit says TRUE, FALSE or nothing at all, and nothing at all is UNDEFINED here, as it is on
    // Velocity for a player no permissions plugin has an opinion about.
    Boolean value = player.permissionValue(permission);
    return value == null ? Tristate.UNDEFINED : Tristate.fromBoolean(value);
  }
  @Override public void deliver(Component message) { player.sendMessage(Texts.toConduit(message)); }
  @Override public void disconnect(Component reason) { player.disconnect(Texts.toConduit(reason)); }

  /** Conduit knows the address, not the port, when the address is forwarded; the port is then 0. */
  @Override public InetSocketAddress getRemoteAddress() { return player.remoteSocketAddress(); }
  @Override public Optional<InetSocketAddress> getVirtualHost() { return Optional.ofNullable(player.virtualHost()); }
  @Override public Optional<String> getRawVirtualHost() { return getVirtualHost().map(InetSocketAddress::getHostString); }
  @Override public ProtocolVersion getProtocolVersion() { return ProtocolVersion.getProtocolVersion(player.protocolVersion()); }
  @Override public boolean isActive() { return environment.conduit.player(player.uniqueId()).filter(live -> live == player).isPresent(); }
  /** Conduit's own states by name; before the handshake is HANDSHAKE, and a closed connection is reported as PLAY. */
  @Override public ProtocolState getProtocolState() {
    return switch (player.connectionState()) {
      case "AWAITING_HANDSHAKE" -> ProtocolState.HANDSHAKE;
      case "STATUS" -> ProtocolState.STATUS;
      case "LOGIN" -> ProtocolState.LOGIN;
      case "CONFIGURATION" -> ProtocolState.CONFIGURATION;
      // ponytail: the state a connection closed in is not kept; a player plugins hold almost always reached Play.
      default -> ProtocolState.PLAY;
    };
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
  /** The profile backends are told: the session server's, or what a GameProfileRequestEvent set. */
  @Override public GameProfile getGameProfile() { return Profiles.toVelocity(player.gameProfile()); }
  @Override public long getPing() { return player.ping(); }

  @Override public HandshakeIntent getHandshakeIntent() { return player.transferred() ? HandshakeIntent.TRANSFER : HandshakeIntent.LOGIN; }
  /** What a plugin set, or else the client's own language; null until the client has sent its settings. */
  @Override public Locale getEffectiveLocale() { return effectiveLocale != null ? effectiveLocale : player.locale().orElse(null); }
  @Override public void setEffectiveLocale(Locale locale) { effectiveLocale = locale; }
  /** The settings the client last sent, as its release has them; the vanilla client's defaults before it has. */
  @Override public PlayerSettings getPlayerSettings() { return new Settings(player.settings().orElse(ClientSettings.defaults())); }
  @Override public boolean hasSentPlayerSettings() { return player.settings().isPresent(); }
  /** The mod list a 1.7-1.12 Forge client last sent in its FML handshake; empty for any other client. */
  @Override public Optional<ModInfo> getModInfo() { return Optional.ofNullable(modInfo); }
  /** What the client last sent on the brand channel; null until it has. */
  @Override public String getClientBrand() { return player.clientBrand().orElse(null); }
  /**
   * The chat key the client sent (see Conduit's Player.chatSession), held by this player; null, the
   * documented answer, for a client that sent none. Not checked against Mojang's key: see VelocityIdentifiedKey.
   */
  @Override public IdentifiedKey getIdentifiedKey() {
    return VelocityIdentifiedKey.of(player.chatSession().orElse(null), getUniqueId(), player.protocolVersion());
  }
  @Override public List<GameProfile.Property> getGameProfileProperties() { return getGameProfile().getProperties(); }
  /** What getGameProfile answers from now on, and what backends are forwarded on the next connection; the current backend keeps the old. */
  @Override public void setGameProfileProperties(List<GameProfile.Property> properties) {
    player.setGameProfileProperties(Profiles.toConduit(new GameProfile(getUniqueId(), getUsername(), properties)).properties());
  }
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
  /** To the backend alone; what a 1.19+ client would have to sign throws (see Conduit's Player.spoofChatInput). */
  @Override public void spoofChatInput(String input) { player.spoofChatInput(input); }
  // Resource packs. A client whose release cannot take one, or drop one (before 1.20.3), is simply
  // not sent anything: the API is honoured, the client is what cannot.
  @Override public void sendResourcePack(String url) { sendResourcePackOffer(new VelocityResourcePackInfo.Builder(url).build()); }
  @Override public void sendResourcePack(String url, byte[] hash) {
    sendResourcePackOffer(new VelocityResourcePackInfo.Builder(url).setHash(hash).build());
  }
  @Override public void sendResourcePackOffer(ResourcePackInfo pack) { player.sendResourcePack(VelocityResourcePackInfo.toConduit(pack)); }
  /** Packs offered through the proxy, the proxy's and the server's, that the client said it loaded. */
  @Override public Collection<ResourcePackInfo> getAppliedResourcePacks() { return packs(true); }
  /** Packs offered through the proxy that the client has not yet loaded, declined or dropped. */
  @Override public Collection<ResourcePackInfo> getPendingResourcePacks() { return packs(false); }
  @Override public ResourcePackInfo getAppliedResourcePack() { return last(packs(true)); }
  @Override public ResourcePackInfo getPendingResourcePack() { return last(packs(false)); }
  private List<ResourcePackInfo> packs(boolean loaded) {
    return player.resourcePacks().stream().filter(offered -> offered.loaded() == loaded)
        .map(offered -> (ResourcePackInfo) VelocityResourcePackInfo.of(offered.pack(), offered.fromServer())).toList();
  }
  private static ResourcePackInfo last(List<ResourcePackInfo> packs) { return packs.isEmpty() ? null : packs.getLast(); }
  /** Adventure's request: every pack in it, with its prompt and required flag, and its callback told each answer. */
  @Override public void sendResourcePacks(ResourcePackRequest request) {
    if (request.replace()) clearResourcePacks();
    gg.tame.conduit.api.text.Text prompt = request.prompt() == null ? gg.tame.conduit.api.text.Text.empty() : Texts.toConduit(request.prompt());
    for (net.kyori.adventure.resource.ResourcePackInfo info : request.packs()) {
      if (request.callback() != ResourcePackCallback.noOp()) packCallbacks.put(info.id(), request.callback());
      player.sendResourcePack(new gg.tame.conduit.api.player.ResourcePack(info.id(), info.uri().toString(), info.hash(), request.required(), prompt));
    }
  }
  @Override public void removeResourcePacks(Iterable<UUID> ids) {
    for (UUID id : ids) if (player.removeResourcePack(id)) packCallbacks.remove(id);
  }
  @Override public void removeResourcePacks(UUID id, UUID... others) {
    removeResourcePacks(List.of(id));
    removeResourcePacks(List.of(others));
  }
  @Override public void clearResourcePacks() {
    if (player.clearResourcePacks()) packCallbacks.clear();
  }
  /** The client answered about a pack; an Adventure callback that asked for it is told, on the adapter's threads. */
  void resourcePackAnswered(UUID id, gg.tame.conduit.api.player.ResourcePack.Status status) {
    ResourcePackCallback callback = status.intermediate() ? packCallbacks.get(id) : packCallbacks.remove(id);
    if (callback == null) return;
    ResourcePackStatus adventure = status == gg.tame.conduit.api.player.ResourcePack.Status.LOADED
        ? ResourcePackStatus.SUCCESSFULLY_LOADED : ResourcePackStatus.valueOf(status.name());
    environment.work.execute(() -> {
      try { callback.packEventReceived(id, adventure, this); }
      catch (RuntimeException failed) { environment.log.log(java.util.logging.Level.WARNING, "A resource pack callback failed", failed); }
    });
  }
  // A client before 1.19.1 has no custom chat completions and is sent nothing.
  @Override public void addCustomChatCompletions(Collection<String> completions) {
    player.updateCustomChatCompletions(gg.tame.conduit.api.player.Player.ChatCompletions.ADD, completions);
  }
  @Override public void removeCustomChatCompletions(Collection<String> completions) {
    player.updateCustomChatCompletions(gg.tame.conduit.api.player.Player.ChatCompletions.REMOVE, completions);
  }
  @Override public void setCustomChatCompletions(Collection<String> completions) {
    player.updateCustomChatCompletions(gg.tame.conduit.api.player.Player.ChatCompletions.SET, completions);
  }
  /** Through PreTransferEvent, as Velocity sends one; a client before 1.20.5 has no Transfer packet, and Velocity throws for it. */
  @Override public void transferToHost(InetSocketAddress address) {
    if (player.protocolVersion() < ProtocolVersion.MINECRAFT_1_20_5.getProtocol()) {
      throw new IllegalArgumentException(getUsername() + " is on " + getProtocolVersion() + "; transfers need 1.20.5 or later");
    }
    player.transferToHost(address.getHostString(), address.getPort());
  }
  // Cookies and server links: a client whose release has none gets an IllegalArgumentException, as Velocity throws.
  /** After CookieStoreEvent, whose result may drop the cookie or change its key or data. */
  @Override public void storeCookie(Key key, byte[] data) {
    requireRelease(ProtocolVersion.MINECRAFT_1_20_5, "cookies");
    if (environment.events.listening(CookieStoreEvent.class)) {
      CookieStoreEvent.ForwardResult result = environment.fireAndWait(new CookieStoreEvent(this, key, data)).getResult();
      if (!result.isAllowed()) return;
      if (result.getKey() != null) key = result.getKey();
      if (result.getData() != null) data = result.getData();
    }
    player.storeCookie(key.asString(), data);
  }
  /** After CookieRequestEvent, whose result may drop the request or change its key. The answer arrives as CookieReceiveEvent, and never reaches the backend. */
  @Override public void requestCookie(Key key) {
    requireRelease(ProtocolVersion.MINECRAFT_1_20_5, "cookies");
    if (environment.events.listening(CookieRequestEvent.class)) {
      CookieRequestEvent.ForwardResult result = environment.fireAndWait(new CookieRequestEvent(this, key)).getResult();
      if (!result.isAllowed()) return;
      if (result.getKey() != null) key = result.getKey();
    }
    player.requestCookie(key.asString());
  }
  @Override public void setServerLinks(List<ServerLink> links) {
    requireRelease(ProtocolVersion.MINECRAFT_1_21, "server links");
    player.setServerLinks(links.stream().map(link -> link.getBuiltInType()
        .map(type -> gg.tame.conduit.api.player.ServerLink.of(gg.tame.conduit.api.player.ServerLink.Type.valueOf(type.name()), link.getUrl()))
        .orElseGet(() -> gg.tame.conduit.api.player.ServerLink.of(Texts.toConduit(link.getCustomLabel().orElseThrow()), link.getUrl())))
        .toList());
  }
  private void requireRelease(ProtocolVersion first, String what) {
    if (player.protocolVersion() < first.getProtocol()) {
      throw new IllegalArgumentException(getUsername() + " is on " + getProtocolVersion() + "; " + what + " need " + first.getVersionIntroducedIn() + " or later");
    }
  }
  // Player and Unsupported.ChatOnly both default these; the class has to pick.
  /** At the player, following them; only 1.19.3+ clients can be sent that (see Player.playSound). */
  @Override public void playSound(Sound sound) { player.playSound(sound(sound)); }
  @Override public void playSound(Sound sound, double x, double y, double z) { player.playSound(sound(sound), x, y, z); }
  /**
   * Following the player, or another player on the same backend by the entity id it gave them; nothing
   * when the other player is elsewhere. Players are the only entities the proxy knows.
   */
  @Override public void playSound(Sound sound, Sound.Emitter emitter) {
    if (emitter == Sound.Emitter.self()) player.playSound(sound(sound));
    else if (emitter instanceof VelocityPlayer other) player.playSound(sound(sound), other.player);
    else throw Unsupported.api("Player.playSound(Sound, Emitter) with an emitter that is not a player");
  }
  @Override public void stopSound(SoundStop stop) {
    player.stopSound(stop.sound() == null ? null : stop.sound().asString(), stop.source() == null ? null : source(stop.source()));
  }
  private static gg.tame.conduit.api.player.Sound sound(Sound sound) {
    return new gg.tame.conduit.api.player.Sound(sound.name().asString(), source(sound.source()), sound.volume(), sound.pitch(), sound.seed());
  }
  // Both name the same eleven sources.
  private static gg.tame.conduit.api.player.Sound.Source source(Sound.Source source) {
    return gg.tame.conduit.api.player.Sound.Source.valueOf(source.name());
  }
  /**
   * The protocol only opens the book in the player's hand, and the proxy does not track what that
   * hand holds, so it could not put the real item back afterwards.
   */
  /** Shown as deleted on a 1.19.1+ client that has the message; nothing for an older one. */
  @Override public void deleteMessage(net.kyori.adventure.chat.SignedMessage.Signature signature) { player.deleteChatMessage(signature.bytes()); }
  @Override public void openBook(Book book) { throw Unsupported.api("Player.openBook"); }
  /** Adventure's DialogLike carries nothing the proxy could send: a dialog is the backend platform's type. */
  @Override public void showDialog(DialogLike dialog) { throw Unsupported.api("Player.showDialog"); }
  /** A 26.2+ client is sent Clear Dialog; any other has none the proxy can close, and is sent nothing. */
  @Override public void closeDialog() { player.closeDialog(); }

  /** The client's settings as Velocity's type; before it has sent any, the vanilla client's defaults. */
  private record Settings(ClientSettings settings) implements PlayerSettings {
    @Override public Locale getLocale() { return settings.locale(); }
    @Override public byte getViewDistance() { return (byte) settings.viewDistance(); }
    @Override public ChatMode getChatMode() {
      return switch (settings.chatMode()) {
        case FULL -> ChatMode.SHOWN;
        case COMMANDS_ONLY -> ChatMode.COMMANDS_ONLY;
        case HIDDEN -> ChatMode.HIDDEN;
      };
    }
    @Override public boolean hasChatColors() { return settings.chatColors(); }
    @Override public SkinParts getSkinParts() { return new SkinParts((byte) settings.skinParts()); }
    @Override public MainHand getMainHand() { return settings.mainHand() == ClientSettings.MainHand.LEFT ? MainHand.LEFT : MainHand.RIGHT; }
    @Override public boolean isClientListingAllowed() { return settings.serverListing(); }
    @Override public boolean isTextFilteringEnabled() { return settings.textFiltering(); }
    @Override public ParticleStatus getParticleStatus() {
      return switch (settings.particles()) {
        case ALL -> ParticleStatus.ALL;
        case DECREASED -> ParticleStatus.DECREASED;
        case MINIMAL -> ParticleStatus.MINIMAL;
      };
    }
  }

  @Override public boolean equals(Object other) { return other instanceof VelocityPlayer that && that.player == player; }
  @Override public int hashCode() { return System.identityHashCode(player); }
  @Override public String toString() { return "VelocityPlayer[" + getUsername() + "]"; }
}
