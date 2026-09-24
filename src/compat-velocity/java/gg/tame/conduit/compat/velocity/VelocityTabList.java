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
import java.util.function.UnaryOperator;
import net.kyori.adventure.text.Component;

/**
 * A player's tab list as Conduit keeps it: the header and footer, the entries plugins put on it, and
 * the backend's own entries as its Player Info packets told the client. Where both have an entry with
 * one id, the proxy's is the one reported and changed.
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
    if (!conduit().removeTabListEntry(id)) conduit().removeBackendTabListEntry(id);
    return removed;
  }

  @Override public boolean containsEntry(UUID id) { return current(id).isPresent(); }
  @Override public Optional<TabListEntry> getEntry(UUID id) { return current(id).map(entry -> new VelocityTabListEntry(this, entry)); }

  @Override public Collection<TabListEntry> getEntries() {
    List<TabListEntry> entries = new ArrayList<>();
    var own = conduit().tabListEntries();
    for (var entry : own) entries.add(new VelocityTabListEntry(this, entry));
    for (var entry : conduit().backendTabListEntries()) {
      if (find(own, entry.id()).isEmpty()) entries.add(new VelocityTabListEntry(this, entry));
    }
    return entries;
  }

  @Override public void clearAll() {
    for (var entry : conduit().tabListEntries()) conduit().removeTabListEntry(entry.id());
    for (var entry : conduit().backendTabListEntries()) conduit().removeBackendTabListEntry(entry.id());
  }

  @Override public TabListEntry buildEntry(GameProfile profile, Component displayName, int latency, int gameMode,
                                           ChatSession chatSession, boolean listed, int listOrder, boolean showHat) {
    return new VelocityTabListEntry(this, profile, displayName, latency, gameMode, listed, listOrder, showHat, chatSession);
  }

  /** The entry with this id as it is now: the proxy's, else the backend's. */
  Optional<gg.tame.conduit.api.player.TabListEntry> current(UUID id) {
    return find(conduit().tabListEntries(), id).or(() -> find(conduit().backendTabListEntries(), id));
  }

  /** Applies {@code change} to the entry with this id where it lives, the proxy's before the backend's; nothing when neither has it. */
  void change(UUID id, UnaryOperator<gg.tame.conduit.api.player.TabListEntry> change) {
    Optional<gg.tame.conduit.api.player.TabListEntry> own = find(conduit().tabListEntries(), id);
    if (own.isPresent()) conduit().addTabListEntry(change.apply(own.get()));
    else find(conduit().backendTabListEntries(), id).ifPresent(entry -> conduit().updateBackendTabListEntry(change.apply(entry)));
  }

  private static Optional<gg.tame.conduit.api.player.TabListEntry> find(List<gg.tame.conduit.api.player.TabListEntry> entries, UUID id) {
    return entries.stream().filter(entry -> entry.id().equals(id)).findFirst();
  }
}
