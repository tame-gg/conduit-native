// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.session;

import gg.tame.conduit.api.player.BossBar;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.api.player.Sound;
import gg.tame.conduit.api.player.TabListEntry;
import gg.tame.conduit.api.player.TitleTimes;
import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.DisplayPackets;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolDefinition;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What the proxy itself shows one client beside whatever its backend shows it: titles, the action
 * bar, boss bars, the tab-list header and footer and its own tab-list entries, written in the
 * client's own protocol. On a translated session they go straight to the client, past the
 * translator, as the proxy's chat does, since they are built in the client's dialect to begin with.
 *
 * <p>A client can only take them while it stands in a world. Before its first Join Game it has none,
 * and a boss-bar update for a bar it was never sent is a crash in the vanilla client. So the display
 * is <em>gated</em>: closed until Join Game, closed again by the packets that take the world away,
 * and on reopening everything the proxy owns is sent again. What closes it:
 * <ul>
 *   <li>Start Configuration. After it a 1.20.2+ client reads Play ids as Configuration ones, so the
 *       gate stays closed until the Join Game that ends the reconfiguration.
 *   <li>Join Game, and Respawn for a client with no Configuration phase. They rebuild the world, and
 *       whether the client keeps its boss bars, tab-list header and tab-list entries through that
 *       depends on its release, so the proxy's are sent again after each one. Adding a bar or an
 *       entry the client still has sets it again, and removing one it no longer has does nothing.
 * </ul>
 * The gate closes <em>before</em> such a packet is written, under the lock every display write holds,
 * so nothing of the proxy's can land between the world going and the re-send.
 *
 * <p>While the gate is closed, bars, the header and entries are state and go out on reopening; titles
 * and the action bar are moments, and the last {@value #MAX_HELD} are held and sent then. Sounds are
 * dropped instead: one played seconds late, in a world loaded since, is a different sound.
 *
 * <p>The backend's own tab-list entries are read, never changed, from its Player Info packets as they
 * reach the client, so plugins can see and edit them; each new backend starts the record afresh at
 * its Join Game.
 */
public final class ClientDisplay {
  /** Writes one packet to the client the way every other clientbound packet goes. */
  public interface Output { void write(byte[] packet) throws IOException; }

  private static final int MAX_HELD = 16;

  private final Player player;
  private final ProtocolDefinition protocol;
  private final Output output;
  private final Object lock = new Object();
  // Everything below is guarded by lock.
  private boolean inWorld;
  private boolean closed;
  private final Map<BossBar, Viewer> bars = new LinkedHashMap<>();
  /** Bars hidden while the gate was closed; the client may still have them. */
  private final List<UUID> hiddenWhileClosed = new ArrayList<>();
  private final Deque<byte[]> held = new ArrayDeque<>();
  /** The proxy's header and footer, or null while it has none. */
  private Text header;
  private Text footer;
  /** The proxy cleared its header while the gate was closed, and the client may still show it. */
  private boolean clearHeaderOnReopen;
  /** The proxy's tab-list entries by id, in the order they were added. */
  private final Map<UUID, TabListEntry> entries = new LinkedHashMap<>();
  /** Entries removed, or given a new profile, while the gate was closed; the client may still list them. */
  private final List<UUID> removedWhileClosed = new ArrayList<>();
  /** The player's own entity id, from the last Join Game the client was written; -1 before one. */
  private int entityId = -1;
  /** The backend's tab-list entries by id, as its Player Info packets told the client since its Join Game. */
  private final Map<UUID, TabListEntry> backendEntries = new LinkedHashMap<>();
  /** 1.7 only: the text each of the proxy's entries is listed under on the client, which names it there. */
  private final Map<UUID, String> legacyShown = new LinkedHashMap<>();
  /** True while this display writes a packet of its own, so that it is not read back as the backend's. */
  private boolean writingOwn;
  /** This client's Player Info (1.7: Player List Item) and Player Info Remove ids; -1 where it has none. */
  private final int infoId;
  private final int infoRemoveId;
  private final boolean legacyList;

  public ClientDisplay(Player player, ProtocolDefinition protocol, Output output) {
    this.player = player;
    this.protocol = protocol;
    // Every write of the display's own happens holding lock, so only the writer ever sees the flag set.
    this.output = packet -> {
      writingOwn = true;
      try { output.write(packet); } finally { writingOwn = false; }
    };
    this.infoId = playId(protocol, PacketKind.PLAY_PLAYER_INFO_UPDATE);
    this.infoRemoveId = playId(protocol, PacketKind.PLAY_PLAYER_INFO_REMOVE);
    this.legacyList = DisplayPackets.legacyTabList(protocol);
  }

  private static int playId(ProtocolDefinition protocol, PacketKind kind) {
    return protocol.defines(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, kind)
        ? protocol.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, kind) : -1;
  }

  // ---- every clientbound packet passes these ------------------------------------------------

  /** Before {@code packet} is written in {@code state}: closes the gate if it takes the world away. */
  public void beforeWrite(ConnectionState state, byte[] packet) {
    if (!takesWorld(protocol, state, packet)) return;
    synchronized (lock) { inWorld = false; }
  }

  /** After {@code packet} was written in {@code state}: reopens the gate and sends everything again. */
  public void afterWrite(ConnectionState state, byte[] packet) {
    if (state != ConnectionState.PLAY) return;
    if (readBackendEntries(packet)) return;
    if (!rebuildsWorld(protocol, packet)) return;
    synchronized (lock) {
      if (closed) return;
      inWorld = true;
      // Every release's Join Game opens with the player's entity id as an int, and it is the id as
      // this client knows it, since the packet has already been through any translator.
      if (is(protocol, packet, PacketKind.PLAY_LOGIN)) {
        entityId = joinGameEntityId(packet);
        // A new backend, which sends its own list from scratch after its Join Game.
        backendEntries.clear();
      }
      try {
        if (legacyList) syncLegacy();
        for (UUID hidden : hiddenWhileClosed) send(DisplayPackets.bossBarRemove(protocol, hidden));
        for (BossBar bar : bars.keySet()) send(DisplayPackets.bossBarAdd(protocol, bar));
        if (header != null || clearHeaderOnReopen) send(DisplayPackets.playerListHeaderAndFooter(protocol, header, footer));
        send(DisplayPackets.tabListRemove(protocol, removedWhileClosed));
        send(DisplayPackets.tabListAdd(protocol, List.copyOf(entries.values())));
        for (byte[] moment : held) output.write(moment);
      } catch (IOException gone) {
        // The client is going away; the session notices that on its own.
      }
      hiddenWhileClosed.clear();
      clearHeaderOnReopen = false;
      removedWhileClosed.clear();
      held.clear();
    }
  }

  /**
   * Reads a backend's Player Info packet as it passes, into what {@link #backendEntries} reports;
   * true if it was one. Only these ids are decoded; any other packet costs one id read. The display's
   * own packets are not the backend's and are skipped. A packet that cannot be read leaves the record
   * as far as it got, and the packet itself has already gone to the client unchanged.
   */
  private boolean readBackendEntries(byte[] packet) {
    int id;
    try { id = PlayPackets.peekId(packet); } catch (IOException unreadable) { return false; }
    if (id < 0 || (id != infoId && id != infoRemoveId)) return false;
    synchronized (lock) {
      if (writingOwn || closed) return true;
      try {
        DisplayPackets.readPlayerInfo(protocol, packet, backendEntries);
      } catch (IOException | RuntimeException unreadable) {
        // The rest of this packet is not known; the backend's next one for an entry puts it right.
      }
    }
    return true;
  }

  /** Whether {@code packet}, about to be written in {@code state}, takes the client's world away. Shared with the resource packs. */
  static boolean takesWorld(ProtocolDefinition protocol, ConnectionState state, byte[] packet) {
    return state == ConnectionState.PLAY && (rebuildsWorld(protocol, packet) || is(protocol, packet, PacketKind.PLAY_START_CONFIGURATION));
  }

  /** Whether {@code packet}, written in Play, gives the client a world again. */
  static boolean rebuildsWorld(ProtocolDefinition protocol, byte[] packet) {
    return is(protocol, packet, PacketKind.PLAY_LOGIN) || (!protocol.hasConfiguration() && is(protocol, packet, PacketKind.PLAY_RESPAWN));
  }

  private static boolean is(ProtocolDefinition protocol, byte[] packet, PacketKind kind) {
    try {
      return protocol.defines(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, kind)
          && protocol.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PlayPackets.peekId(packet), kind);
    } catch (IOException unreadable) {
      return false;
    }
  }

  // ---- titles and the action bar ------------------------------------------------------------

  public void actionBar(Text text) { moment(() -> DisplayPackets.actionBar(protocol, text)); }
  public void title(Text title) { moment(() -> DisplayPackets.title(protocol, title)); }
  public void subtitle(Text subtitle) { moment(() -> DisplayPackets.subtitle(protocol, subtitle)); }
  public void titleTimes(TitleTimes times) {
    moment(() -> DisplayPackets.titleTimes(protocol, times.fadeInTicks(), times.stayTicks(), times.fadeOutTicks()));
  }
  public void clearTitle(boolean reset) { moment(() -> DisplayPackets.clearTitle(protocol, reset)); }

  private interface Build { Optional<byte[]> packet() throws IOException; }

  private void moment(Build build) {
    synchronized (lock) {
      if (closed) return;
      try {
        Optional<byte[]> packet = build.packet();
        if (packet.isEmpty()) return;
        if (inWorld) {
          output.write(packet.get());
        } else {
          if (held.size() == MAX_HELD) held.removeFirst();
          held.addLast(packet.get());
        }
      } catch (IOException gone) {
        // As in afterWrite: a failed write is the session's to notice.
      }
    }
  }

  // ---- boss bars ----------------------------------------------------------------------------

  public void show(BossBar bar) {
    synchronized (lock) {
      if (closed || bars.containsKey(bar)) return;
      Viewer viewer = new Viewer();
      bars.put(bar, viewer);
      hiddenWhileClosed.remove(bar.id());
      bar.addListener(viewer);
      if (inWorld) trySend(() -> DisplayPackets.bossBarAdd(protocol, bar));
    }
  }

  public void hide(BossBar bar) {
    synchronized (lock) {
      Viewer viewer = bars.remove(bar);
      if (viewer == null) return;
      bar.removeListener(viewer);
      if (closed) return;
      if (inWorld) trySend(() -> DisplayPackets.bossBarRemove(protocol, bar.id()));
      else hiddenWhileClosed.add(bar.id());
    }
  }

  /** This client's registration on one bar; the bar calls it on the thread that changed it. */
  private final class Viewer implements BossBar.Viewer {
    @Override public Player player() { return player; }
    @Override public void changed(BossBar bar, BossBar.Change change) {
      synchronized (lock) {
        // While the gate is closed the change is already in the bar, and the re-send carries it.
        if (closed || !inWorld || bars.get(bar) != this) return;
        trySend(() -> DisplayPackets.bossBarUpdate(protocol, bar, change));
      }
    }
  }

  // ---- tab-list header and footer -----------------------------------------------------------

  public void headerAndFooter(Text header, Text footer) {
    Text top = header == null ? Text.empty() : header;
    Text bottom = footer == null ? Text.empty() : footer;
    boolean none = top.plain().isEmpty() && bottom.plain().isEmpty();
    synchronized (lock) {
      if (closed) return;
      boolean had = this.header != null;
      this.header = none ? null : top;
      this.footer = none ? null : bottom;
      if (inWorld) trySend(() -> DisplayPackets.playerListHeaderAndFooter(protocol, top, bottom));
      else clearHeaderOnReopen = none && (had || clearHeaderOnReopen);
    }
  }

  public Text header() { synchronized (lock) { return header == null ? Text.empty() : header; } }
  public Text footer() { synchronized (lock) { return footer == null ? Text.empty() : footer; } }

  // ---- tab-list entries ---------------------------------------------------------------------

  public void addEntry(TabListEntry entry) {
    synchronized (lock) {
      if (closed) return;
      TabListEntry before = entries.put(entry.id(), entry);
      if (legacyList) {
        if (inWorld) trySync();
        return;
      }
      boolean newProfile = before != null && !(before.name().equals(entry.name()) && before.properties().equals(entry.properties()));
      if (!inWorld) {
        // The reopen adds every entry as it then is; a changed profile has to go first, as below.
        if (newProfile && !removedWhileClosed.contains(entry.id())) removedWhileClosed.add(entry.id());
        return;
      }
      try {
        if (before == null) {
          send(DisplayPackets.tabListAdd(protocol, List.of(entry)));
        } else if (newProfile) {
          // 1.19.3+ keeps the profile an entry was first added with, so a new name or skin means a new entry.
          send(DisplayPackets.tabListRemove(protocol, List.of(entry.id())));
          send(DisplayPackets.tabListAdd(protocol, List.of(entry)));
        } else {
          for (byte[] update : DisplayPackets.tabListUpdate(protocol, before, entry)) output.write(update);
        }
      } catch (IOException gone) {
        // The session notices a dead client on its own.
      }
    }
  }

  public boolean removeEntry(UUID id) {
    synchronized (lock) {
      if (entries.remove(id) == null) return false;
      if (closed) return true;
      if (legacyList) {
        if (inWorld) trySync();
        return true;
      }
      if (inWorld) trySend(() -> DisplayPackets.tabListRemove(protocol, List.of(id)));
      else if (!removedWhileClosed.contains(id)) removedWhileClosed.add(id);
      return true;
    }
  }

  public List<TabListEntry> entries() { synchronized (lock) { return List.copyOf(entries.values()); } }

  /**
   * 1.7 names an entry by the text it shows, so the proxy's list is put right by name: an entry gone,
   * or shown under new text, is taken off under its old text, then every entry is listed again.
   * Called holding lock, with the client in a world.
   */
  // ponytail: resends every proxy entry on each change, fine for the handful a plugin adds; send only the changed one if 1.7 lists grow.
  private void syncLegacy() throws IOException {
    for (var shown = legacyShown.entrySet().iterator(); shown.hasNext(); ) {
      Map.Entry<UUID, String> listed = shown.next();
      TabListEntry entry = entries.get(listed.getKey());
      if (entry != null && DisplayPackets.legacyListName(entry).equals(listed.getValue())) continue;
      output.write(DisplayPackets.legacyListItem(protocol, listed.getValue(), false, 0));
      shown.remove();
    }
    for (TabListEntry entry : entries.values()) {
      String name = DisplayPackets.legacyListName(entry);
      output.write(DisplayPackets.legacyListItem(protocol, name, true, entry.latency()));
      legacyShown.put(entry.id(), name);
    }
  }

  private void trySync() {
    try { syncLegacy(); } catch (IOException gone) { /* the session notices a dead client on its own */ }
  }

  // ---- the backend's tab-list entries -------------------------------------------------------

  /** The backend's entries on this client's tab list, as it last told the client, in the order it added them. */
  public List<TabListEntry> backendEntries() { synchronized (lock) { return List.copyOf(backendEntries.values()); } }

  /**
   * Shows the backend's entry with {@code entry}'s id as {@code entry} says: display name, latency,
   * game mode, listed, list order and hat, as far as the client's release has them; the profile stays
   * the backend's. A 1.7 client is sent the latency only, under the name the backend listed. The
   * backend's next update of a field replaces the proxy's. False when the backend has no such entry.
   */
  public boolean updateBackendEntry(TabListEntry entry) {
    synchronized (lock) {
      TabListEntry before = backendEntries.get(entry.id());
      if (before == null || closed) return false;
      TabListEntry after = new TabListEntry(before.id(), before.name(), before.properties(), entry.displayName(), entry.latency(),
          entry.gameMode(), entry.listed(), entry.listOrder(), entry.showHat());
      backendEntries.put(after.id(), after);
      if (!inWorld) return true;
      try {
        if (legacyList) output.write(DisplayPackets.legacyListItem(protocol, before.name(), true, after.latency()));
        else for (byte[] update : DisplayPackets.tabListUpdate(protocol, before, after)) output.write(update);
      } catch (IOException gone) {
        // The session notices a dead client on its own.
      }
      return true;
    }
  }

  /** Takes the backend's entry with this id off the client's list; false when the backend has none. */
  public boolean removeBackendEntry(UUID id) {
    synchronized (lock) {
      TabListEntry removed = backendEntries.remove(id);
      if (removed == null || closed) return false;
      if (!inWorld) return true;
      try {
        if (legacyList) output.write(DisplayPackets.legacyListItem(protocol, removed.name(), false, 0));
        else send(DisplayPackets.tabListRemove(protocol, List.of(id)));
      } catch (IOException gone) {
        // The session notices a dead client on its own.
      }
      return true;
    }
  }

  // ---- sounds -------------------------------------------------------------------------------

  /** At the player, following them: an entity sound on their own entity. */
  public void playSound(Sound sound) {
    synchronized (lock) { playSoundFollowing(sound, entityId); }
  }

  /** Following entity {@code entity} of this client's world: another player's, from their own {@link #entityId()}. */
  public void playSound(Sound sound, int entity) {
    synchronized (lock) { playSoundFollowing(sound, entity); }
  }

  /** Called holding lock. */
  private void playSoundFollowing(Sound sound, int entity) {
    if (closed || !inWorld || entity < 0) return;
    trySend(() -> DisplayPackets.soundFollowing(protocol, sound, entity, seed(sound)));
  }

  /** The player's own entity id on their backend, from the last Join Game; -1 before one. */
  public int entityId() { synchronized (lock) { return entityId; } }

  public void playSound(Sound sound, double x, double y, double z) {
    synchronized (lock) {
      if (closed || !inWorld) return;
      trySend(() -> DisplayPackets.soundAt(protocol, sound, x, y, z, seed(sound)));
    }
  }

  public void stopSound(String name, Sound.Source source) {
    synchronized (lock) {
      if (closed || !inWorld) return;
      trySend(() -> DisplayPackets.stopSound(protocol, name, source));
    }
  }

  private static int joinGameEntityId(byte[] packet) {
    try {
      byte[] body = PlayPackets.body(packet);
      return body.length >= 4 ? java.nio.ByteBuffer.wrap(body).getInt() : -1;
    } catch (IOException unreadable) {
      return -1;
    }
  }

  private static long seed(Sound sound) {
    return sound.seed().orElseGet(() -> java.util.concurrent.ThreadLocalRandom.current().nextLong());
  }

  // ---- the end ------------------------------------------------------------------------------

  /** The session is over: stop listening to every bar, so no bar keeps this client. */
  public void close() {
    synchronized (lock) {
      closed = true;
      for (Map.Entry<BossBar, Viewer> shown : bars.entrySet()) shown.getKey().removeListener(shown.getValue());
      bars.clear();
      hiddenWhileClosed.clear();
      entries.clear();
      removedWhileClosed.clear();
      backendEntries.clear();
      legacyShown.clear();
      held.clear();
    }
  }

  /** Called holding lock. */
  private void trySend(Build build) {
    try { send(build.packet()); } catch (IOException gone) { /* the session notices a dead client on its own */ }
  }

  private void send(Optional<byte[]> packet) throws IOException {
    if (packet.isPresent()) output.write(packet.get());
  }
}
