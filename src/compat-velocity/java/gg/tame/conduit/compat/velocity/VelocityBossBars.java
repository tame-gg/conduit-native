// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.compat.velocity;

import gg.tame.conduit.api.player.Player;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;

/**
 * Adventure boss bars as Conduit's. Each Adventure bar someone is viewing has one native bar, kept in
 * step by a listener on the Adventure bar. That listener is added with the bar's first viewer and
 * removed with its last, whether the last one hid it or disconnected, so a plugin's bar never keeps a
 * reference into the proxy after nobody sees it.
 */
final class VelocityBossBars {
  // ponytail: one lock for every bar; per-bar locks if show/hide ever contend.
  private final Map<BossBar, Shown> shown = new IdentityHashMap<>();
  /**
   * Players already forgotten. A plugin that kept one past their disconnect and showed them a bar made
   * them its viewer again, and nothing would forget them twice: the bar held their session for good.
   * Weak, so remembering who left holds nobody either.
   */
  private final Set<Player> gone = Collections.newSetFromMap(new WeakHashMap<>());

  synchronized void show(Player viewer, BossBar bar) {
    if (gone.contains(viewer)) return;
    Shown entry = shown.get(bar);
    if (entry == null) {
      entry = new Shown(bar);
      shown.put(bar, entry);
      bar.addListener(entry);
    }
    if (entry.viewers.add(viewer)) viewer.showBossBar(entry.bar);
  }

  synchronized void hide(Player viewer, BossBar bar) {
    Shown entry = shown.get(bar);
    if (entry == null || !entry.viewers.remove(viewer)) return;
    viewer.hideBossBar(entry.bar);
    if (entry.viewers.isEmpty()) release(bar, entry);
  }

  /** The player is gone: every bar they viewed forgets them. Their session already let go of its side. */
  synchronized void forget(Player viewer) {
    gone.add(viewer);
    for (Iterator<Map.Entry<BossBar, Shown>> bars = shown.entrySet().iterator(); bars.hasNext(); ) {
      Map.Entry<BossBar, Shown> entry = bars.next();
      if (!entry.getValue().viewers.remove(viewer) || !entry.getValue().viewers.isEmpty()) continue;
      entry.getKey().removeListener(entry.getValue());
      bars.remove();
    }
  }

  private void release(BossBar bar, Shown entry) {
    bar.removeListener(entry);
    shown.remove(bar);
  }

  /** The native bar for one Adventure bar, and the listener that copies every change across. */
  private static final class Shown implements BossBar.Listener {
    final gg.tame.conduit.api.player.BossBar bar;
    final Set<Player> viewers = new HashSet<>();

    Shown(BossBar adventure) {
      bar = new gg.tame.conduit.api.player.BossBar(Texts.toConduit(adventure.name()), adventure.progress(),
          color(adventure.color()), overlay(adventure.overlay()), flags(adventure.flags()));
    }

    @Override public void bossBarNameChanged(BossBar adventure, Component oldName, Component newName) {
      bar.name(Texts.toConduit(newName));
    }
    @Override public void bossBarProgressChanged(BossBar adventure, float oldProgress, float newProgress) {
      bar.progress(newProgress);
    }
    @Override public void bossBarColorChanged(BossBar adventure, BossBar.Color oldColor, BossBar.Color newColor) {
      bar.color(color(newColor));
    }
    @Override public void bossBarOverlayChanged(BossBar adventure, BossBar.Overlay oldOverlay, BossBar.Overlay newOverlay) {
      bar.overlay(overlay(newOverlay));
    }
    @Override public void bossBarFlagsChanged(BossBar adventure, Set<BossBar.Flag> added, Set<BossBar.Flag> removed) {
      bar.flags(flags(adventure.flags()));
    }
  }

  // Both APIs name the same colours, overlays and flags.
  private static gg.tame.conduit.api.player.BossBar.Color color(BossBar.Color color) {
    return gg.tame.conduit.api.player.BossBar.Color.valueOf(color.name());
  }

  private static gg.tame.conduit.api.player.BossBar.Overlay overlay(BossBar.Overlay overlay) {
    return gg.tame.conduit.api.player.BossBar.Overlay.valueOf(overlay.name());
  }

  private static Set<gg.tame.conduit.api.player.BossBar.Flag> flags(Set<BossBar.Flag> flags) {
    Set<gg.tame.conduit.api.player.BossBar.Flag> result = EnumSet.noneOf(gg.tame.conduit.api.player.BossBar.Flag.class);
    for (BossBar.Flag flag : flags) result.add(gg.tame.conduit.api.player.BossBar.Flag.valueOf(flag.name()));
    return result;
  }
}
