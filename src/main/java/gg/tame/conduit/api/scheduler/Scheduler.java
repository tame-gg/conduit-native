// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.scheduler;

import gg.tame.conduit.api.plugin.Plugin;
import java.time.Duration;

/**
 * Plugin tasks. Every task runs on threads that belong to its plugin -- never on a player's
 * connection thread, and never on another plugin's -- so a task that blocks holds up only its own
 * plugin's work. A repeating task never overlaps itself: a run still going when the next falls due
 * skips that one. A task that throws is logged and, if repeating, still runs next time. Disabling a
 * plugin cancels its tasks, refuses it new ones, and ends its threads once a running task returns.
 */
public interface Scheduler {
  TaskBuilder buildTask(Plugin plugin, Runnable task);
  /** Cancels every task {@code plugin} has scheduled. It may schedule new ones afterwards. */
  void cancel(Plugin plugin);

  interface TaskBuilder {
    TaskBuilder delay(Duration delay);
    /** Runs the task every {@code interval} after its first run, until cancelled. */
    TaskBuilder repeat(Duration interval);
    /** Kept for readability: every task already runs off the connection threads, on its plugin's own. */
    TaskBuilder async();
    /** @throws IllegalStateException when the plugin has been disabled or the proxy is shutting down */
    ScheduledTask schedule();
  }
}
