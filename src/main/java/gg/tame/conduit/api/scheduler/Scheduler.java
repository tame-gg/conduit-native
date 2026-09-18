package gg.tame.conduit.api.scheduler;

import gg.tame.conduit.api.plugin.Plugin;
import java.time.Duration;

/**
 * Plugin tasks. Every task runs on the proxy's scheduler threads, never on a player's connection
 * thread, so a task may block briefly but should not hold a thread for long: the threads are
 * shared by every plugin. A task that throws is logged and, if repeating, still runs next time.
 * Disabling a plugin cancels its tasks and refuses it new ones.
 */
public interface Scheduler {
  TaskBuilder buildTask(Plugin plugin, Runnable task);
  /** Cancels every task {@code plugin} has scheduled. It may schedule new ones afterwards. */
  void cancel(Plugin plugin);

  interface TaskBuilder {
    TaskBuilder delay(Duration delay);
    /** Runs the task every {@code interval} after its first run, until cancelled. */
    TaskBuilder repeat(Duration interval);
    /** Kept for readability: every task already runs off the connection threads. */
    TaskBuilder async();
    /** @throws IllegalStateException when the plugin has been disabled or the proxy is shutting down */
    ScheduledTask schedule();
  }
}
