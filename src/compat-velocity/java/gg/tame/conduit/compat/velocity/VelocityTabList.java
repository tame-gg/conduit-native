// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.compat.velocity;

import com.velocitypowered.api.proxy.player.ChatSession;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.proxy.player.TabListEntry;
import com.velocitypowered.api.util.GameProfile;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;

/**
 * A player's tab list as Conduit keeps it: the header and footer, and the entries plugins put on it.
 * The backend's own entries are not tracked, so {@link #getEntries} lists only the proxy's, and
 * {@link #clearAll} removes only those.
 */
final class VelocityTabList implements TabList {
  final VelocityPlayer player;

  VelocityTabList(VelocityPlayer player) { this.player = player; }

  private gg.tame.conduit.api.player.Player conduit() { return player.nativePlayer(); }

  @Override public void setHeaderAndFooter(Component header, Component footer) { player.sendPlayerListHeaderAndFooter(header, footer); }
  @Override public void clearHeaderAndFooter() { player.clearPlayerListHeaderAndFooter(); }

  @Override public void addEntry(TabListEntry entry) {
    if (!(entry.getTabList() instanceof VelocityTabList list) || !list.player.equals(player)) {
      throw new IllegalArgumentException("the entry was built for another player's tab list");
    }
    conduit().addTabListEntry(VelocityTabListEntry.toConduit(entry));
  }

  @Override public Optional<TabListEntry> removeEntry(UUID id) {
    Optional<TabListEntry> removed = getEntry(id);
    conduit().removeTabListEntry(id);
    return removed;
  }

  @Override public boolean containsEntry(UUID id) { return current(id).isPresent(); }
  @Override public Optional<TabListEntry> getEntry(UUID id) { return current(id).map(entry -> new VelocityTabListEntry(this, entry)); }

  @Override public Collection<TabListEntry> getEntries() {
    List<TabListEntry> entries = new ArrayList<>();
    for (var entry : conduit().tabListEntries()) entries.add(new VelocityTabListEntry(this, entry));
    return entries;
  }

  @Override public void clearAll() {
    for (var entry : conduit().tabListEntries()) conduit().removeTabListEntry(entry.id());
  }

  @Override public TabListEntry buildEntry(GameProfile profile, Component displayName, int latency, int gameMode,
                                           ChatSession chatSession, boolean listed, int listOrder, boolean showHat) {
    // The proxy's entries carry no chat session: there is no signed chat behind them to vouch for.
    if (chatSession != null) throw Unsupported.api("TabListEntry chat sessions");
    return new VelocityTabListEntry(this, profile, displayName, latency, gameMode, listed, listOrder, showHat);
  }

  Optional<gg.tame.conduit.api.player.TabListEntry> current(UUID id) {
    return conduit().tabListEntries().stream().filter(entry -> entry.id().equals(id)).findFirst();
  }
}
