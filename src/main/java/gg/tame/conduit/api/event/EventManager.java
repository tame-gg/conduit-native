// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event;

import gg.tame.conduit.api.plugin.Plugin;

/**
 * Synchronous event bus.
 *
 * <p>{@link #fire} runs every matching listener on the calling thread, in {@link Subscribe#order}
 * order, and returns the event once all of them have: the caller then reads its cancellation or
 * result. Each event's Javadoc names the thread it is fired on. Player events come from that
 * player's connection threads, so a listener that blocks stalls that player, and one that waits on
 * another player's connection can deadlock both. Hand slow work to the scheduler.
 *
 * <p>A listener that throws is logged and skipped; the remaining listeners still run and the event
 * keeps whatever state the throwing listener had given it.
 */
public interface EventManager {
  /**
   * Registers every {@link Subscribe} method of {@code listener}, owned by {@code plugin}.
   *
   * @throws IllegalArgumentException when a {@code @Subscribe} method does not take exactly one Event
   */
  void register(Plugin plugin, Object listener);
  /** Removes every listener {@code plugin} registered. Disabling a plugin does this for it. */
  void unregister(Plugin plugin);
  <E extends Event> E fire(E event);
}
