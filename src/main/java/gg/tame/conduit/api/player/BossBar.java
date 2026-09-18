// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.player;

import gg.tame.conduit.api.text.Text;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A boss bar the proxy owns. Show it with {@link Player#showBossBar}; every change made here is then
 * pushed to each player it is shown to, and the proxy shows it again after a server switch, where the
 * client may have dropped it. A player who disconnects stops viewing it.
 *
 * <p>Boss bars exist from 1.9. A 1.7 or 1.8 client has no boss-bar packet, so showing one a bar
 * sends nothing, though the player is still counted as a viewer.
 *
 * <p>Every bar has a random version-8 (RFC 9562 "custom") UUID. Vanilla and the common server
 * platforms name their own bars with version-4 random UUIDs, so a proxy bar cannot replace or remove
 * a bar the backend showed, nor the other way round.
 *
 * <p>Safe to use from any thread.
 */
public final class BossBar {
  /** Bar colours. */
  public enum Color { PINK, BLUE, RED, GREEN, YELLOW, PURPLE, WHITE }
  /** Whether the bar is one piece or divided into notches. */
  public enum Overlay { PROGRESS, NOTCHED_6, NOTCHED_10, NOTCHED_12, NOTCHED_20 }
  /** Effects the client applies while the bar is shown. */
  public enum Flag { DARKEN_SCREEN, PLAY_BOSS_MUSIC, CREATE_WORLD_FOG }
  /** What a change touched. Colour and overlay travel together, as one style. */
  public enum Change { NAME, PROGRESS, STYLE, FLAGS }

  /** Told about every change, on the thread that made it. */
  @FunctionalInterface
  public interface Listener {
    void changed(BossBar bar, Change change);
  }

  /**
   * A listener that is a player the bar is shown to. The proxy registers one for each player in
   * {@link Player#showBossBar}; {@link #viewers()} is the players behind them.
   */
  public interface Viewer extends Listener {
    Player player();
  }

  private final UUID id;
  private volatile Text name;
  private volatile float progress;
  private volatile Color color;
  private volatile Overlay overlay;
  private volatile Set<Flag> flags;
  private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

  public BossBar(Text name, float progress, Color color, Overlay overlay) {
    this(name, progress, color, overlay, Set.of());
  }

  public BossBar(Text name, float progress, Color color, Overlay overlay, Set<Flag> flags) {
    UUID random = UUID.randomUUID();
    // Same random bits, with the version nibble set to 8 instead of 4.
    this.id = new UUID((random.getMostSignificantBits() & ~0xF000L) | 0x8000L, random.getLeastSignificantBits());
    this.name = name == null ? Text.empty() : name;
    this.progress = checked(progress);
    this.color = Objects.requireNonNull(color, "color");
    this.overlay = Objects.requireNonNull(overlay, "overlay");
    this.flags = copy(flags);
  }

  public UUID id() { return id; }
  public Text name() { return name; }
  /** From 0 (empty) to 1 (full). */
  public float progress() { return progress; }
  public Color color() { return color; }
  public Overlay overlay() { return overlay; }
  public Set<Flag> flags() { return flags; }

  public BossBar name(Text name) {
    Text next = name == null ? Text.empty() : name;
    if (next.equals(this.name)) return this;
    this.name = next;
    return fire(Change.NAME);
  }

  public BossBar progress(float progress) {
    float next = checked(progress);
    if (next == this.progress) return this;
    this.progress = next;
    return fire(Change.PROGRESS);
  }

  public BossBar color(Color color) {
    Objects.requireNonNull(color, "color");
    if (color == this.color) return this;
    this.color = color;
    return fire(Change.STYLE);
  }

  public BossBar overlay(Overlay overlay) {
    Objects.requireNonNull(overlay, "overlay");
    if (overlay == this.overlay) return this;
    this.overlay = overlay;
    return fire(Change.STYLE);
  }

  public BossBar flags(Set<Flag> flags) {
    Set<Flag> next = copy(flags);
    if (next.equals(this.flags)) return this;
    this.flags = next;
    return fire(Change.FLAGS);
  }

  /** Shows this bar to {@code player}; the same as {@link Player#showBossBar}. */
  public BossBar addViewer(Player player) {
    player.showBossBar(this);
    return this;
  }

  /** Stops showing this bar to {@code player}; the same as {@link Player#hideBossBar}. */
  public BossBar removeViewer(Player player) {
    player.hideBossBar(this);
    return this;
  }

  /** The players this bar is shown to right now. */
  public Set<Player> viewers() {
    Set<Player> viewers = new LinkedHashSet<>();
    for (Listener listener : listeners) if (listener instanceof Viewer viewer) viewers.add(viewer.player());
    return Set.copyOf(viewers);
  }

  public void addListener(Listener listener) {
    Objects.requireNonNull(listener, "listener");
    listeners.addIfAbsent(listener);
  }

  public void removeListener(Listener listener) {
    listeners.remove(listener);
  }

  private BossBar fire(Change change) {
    for (Listener listener : listeners) listener.changed(this, change);
    return this;
  }

  private static float checked(float progress) {
    if (!(progress >= 0f && progress <= 1f)) throw new IllegalArgumentException("progress must be between 0 and 1, was " + progress);
    return progress;
  }

  private static Set<Flag> copy(Set<Flag> flags) {
    return flags == null || flags.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(flags));
  }

  @Override public String toString() { return "BossBar[" + id + " " + name.plain() + "]"; }
}
