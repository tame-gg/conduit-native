// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.compat.velocity;

import com.velocitypowered.api.proxy.player.ChatSession;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.proxy.player.TabListEntry;
import com.velocitypowered.api.util.GameProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import net.kyori.adventure.text.Component;

/**
 * One entry of a player's tab list, the proxy's or the backend's. A setter changes this view and, when
 * the entry is on the tab list, the entry itself, read fresh from Conduit so that two views of one
 * entry never undo each other's changes. A backend entry's change is sent to the client, and lasts
 * until the backend next updates that field.
 */
final class VelocityTabListEntry implements TabListEntry {
  private final VelocityTabList tabList;
  private final GameProfile profile;
  private volatile Component displayName;
  private volatile int latency;
  private volatile int gameMode;
  private volatile boolean listed;
  private volatile int listOrder;
  private volatile boolean showHat;
  private final ChatSession chatSession;

  VelocityTabListEntry(VelocityTabList tabList, GameProfile profile, Component displayName, int latency, int gameMode,
                       boolean listed, int listOrder, boolean showHat) {
    this(tabList, profile, displayName, latency, gameMode, listed, listOrder, showHat, null);
  }

  VelocityTabListEntry(VelocityTabList tabList, GameProfile profile, Component displayName, int latency, int gameMode,
                       boolean listed, int listOrder, boolean showHat, ChatSession chatSession) {
    this.chatSession = chatSession;
    this.tabList = tabList;
    this.profile = profile;
    this.displayName = displayName;
    this.latency = latency;
    this.gameMode = gameMode;
    this.listed = listed;
    this.listOrder = listOrder;
    this.showHat = showHat;
  }

  VelocityTabListEntry(VelocityTabList tabList, gg.tame.conduit.api.player.TabListEntry entry) {
    this(tabList, profile(entry), entry.displayName() == null ? null : Texts.toAdventure(entry.displayName()),
        entry.latency(), entry.gameMode(), entry.listed(), entry.listOrder(), entry.showHat(),
        VelocityIdentifiedKey.Session.of(entry.chatSession(), entry.id(), tabList.player.getProtocolVersion().getProtocol()));
  }

  @Override public TabList getTabList() { return tabList; }
  @Override public GameProfile getProfile() { return profile; }
  /** The session a plugin gave the entry, which a 1.19.3+ client is sent with it (a 1.19-1.19.2 client, its key); null for none. */
  @Override public ChatSession getChatSession() { return chatSession; }
  @Override public Optional<Component> getDisplayNameComponent() { return Optional.ofNullable(displayName); }
  @Override public int getLatency() { return latency; }
  @Override public int getGameMode() { return gameMode; }
  @Override public boolean isListed() { return listed; }
  @Override public int getListOrder() { return listOrder; }
  @Override public boolean isShowHat() { return showHat; }

  @Override public TabListEntry setDisplayName(Component displayName) {
    this.displayName = displayName;
    return push(entry -> copy(entry, displayName == null ? null : Texts.toConduit(displayName), entry.latency(), entry.gameMode(),
        entry.listed(), entry.listOrder(), entry.showHat()));
  }
  @Override public TabListEntry setLatency(int latency) {
    this.latency = latency;
    return push(entry -> copy(entry, entry.displayName(), latency, entry.gameMode(), entry.listed(), entry.listOrder(), entry.showHat()));
  }
  @Override public TabListEntry setGameMode(int gameMode) {
    this.gameMode = gameMode;
    return push(entry -> copy(entry, entry.displayName(), entry.latency(), gameMode, entry.listed(), entry.listOrder(), entry.showHat()));
  }
  @Override public TabListEntry setListed(boolean listed) {
    this.listed = listed;
    return push(entry -> copy(entry, entry.displayName(), entry.latency(), entry.gameMode(), listed, entry.listOrder(), entry.showHat()));
  }
  @Override public TabListEntry setListOrder(int listOrder) {
    this.listOrder = listOrder;
    return push(entry -> copy(entry, entry.displayName(), entry.latency(), entry.gameMode(), entry.listed(), listOrder, entry.showHat()));
  }
  @Override public TabListEntry setShowHat(boolean showHat) {
    this.showHat = showHat;
    return push(entry -> copy(entry, entry.displayName(), entry.latency(), entry.gameMode(), entry.listed(), entry.listOrder(), showHat));
  }

  private TabListEntry push(UnaryOperator<gg.tame.conduit.api.player.TabListEntry> change) {
    tabList.change(profile.getId(), change);
    return this;
  }

  private static gg.tame.conduit.api.player.TabListEntry copy(gg.tame.conduit.api.player.TabListEntry entry,
      gg.tame.conduit.api.text.Text displayName, int latency, int gameMode, boolean listed, int listOrder, boolean showHat) {
    return new gg.tame.conduit.api.player.TabListEntry(entry.id(), entry.name(), entry.properties(), displayName, latency, gameMode,
        listed, listOrder, showHat, entry.chatSession());
  }

  /** Any Velocity entry as Conduit's; its profile names it. */
  static gg.tame.conduit.api.player.TabListEntry toConduit(TabListEntry entry) {
    GameProfile profile = entry.getProfile();
    List<gg.tame.conduit.api.player.TabListEntry.Property> properties = new ArrayList<>();
    for (GameProfile.Property property : profile.getProperties()) {
      String signature = property.getSignature();
      properties.add(new gg.tame.conduit.api.player.TabListEntry.Property(property.getName(), property.getValue(),
          signature == null || signature.isEmpty() ? null : signature));
    }
    return new gg.tame.conduit.api.player.TabListEntry(profile.getId(), profile.getName(), properties,
        entry.getDisplayNameComponent().map(Texts::toConduit).orElse(null), entry.getLatency(), entry.getGameMode(),
        entry.isListed(), entry.getListOrder(), entry.isShowHat(), chatSession(entry.getChatSession()));
  }

  /** A plugin's session as Conduit's; none for a session without a key, which gives a client nothing to check chat with. */
  private static gg.tame.conduit.api.player.ChatSession chatSession(ChatSession session) {
    if (session == null || session.getIdentifiedKey() == null) return null;
    return VelocityIdentifiedKey.toConduit(session.getSessionId(), session.getIdentifiedKey());
  }

  private static GameProfile profile(gg.tame.conduit.api.player.TabListEntry entry) {
    List<GameProfile.Property> properties = new ArrayList<>();
    for (var property : entry.properties()) {
      properties.add(new GameProfile.Property(property.name(), property.value(), property.signature() == null ? "" : property.signature()));
    }
    return new GameProfile(entry.id(), entry.name(), properties);
  }
}
