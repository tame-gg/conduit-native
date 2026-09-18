package gg.tame.conduit.scheduler;

import gg.tame.conduit.api.plugin.Plugin;
import gg.tame.conduit.api.scheduler.ScheduledTask;
import gg.tame.conduit.api.scheduler.Scheduler;
import gg.tame.conduit.log.ConduitLog;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Plugin tasks never run on player socket threads. */
public final class ConduitScheduler implements Scheduler, AutoCloseable {
  // ponytail: two shared platform threads; a plugin task that blocks for long delays every other
  // plugin's tasks. Hand execution to a per-plugin pool if that starts to matter.
  private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, runnable -> {
    Thread thread = new Thread(runnable, "conduit-scheduler");
    thread.setDaemon(true);
    return thread;
  });
  private final Set<Task> tasks = ConcurrentHashMap.newKeySet();
  /**
   * Plugins that were disabled. A task already running when its plugin went away used to schedule
   * its successor after the cancel had swept past, and that chain then ran for the life of the
   * process against a closed class loader. Weak, so a disabled plugin can still be collected.
   */
  private final Set<Plugin> retired = Collections.newSetFromMap(new WeakHashMap<>());
  private final Object lock = new Object();
  @Override public TaskBuilder buildTask(Plugin plugin, Runnable task) { return new Builder(plugin, task); }
  @Override public void cancel(Plugin plugin) {
    for (Task task : new ArrayList<>(tasks)) if (task.plugin == plugin) task.cancel();
  }
  /** Cancels the plugin's tasks and refuses it any new ones; for a plugin being disabled. */
  public void retire(Plugin plugin) {
    synchronized (lock) { retired.add(plugin); }
    cancel(plugin);
  }
  @Override public void close() { executor.shutdownNow(); }
  /** Live tasks, for tests: a finished one-shot task must not stay here. */
  public int taskCount() { return tasks.size(); }

  /** Internal Conduit tasks (status refresh). Not owned by a plugin. */
  public void scheduleSystem(Runnable work, Duration delay, Duration period) {
    long delayMs = Math.max(0, delay.toMillis());
    long periodMs = Math.max(1, period.toMillis());
    executor.scheduleAtFixedRate(() -> {
      try { work.run(); }
      catch (RuntimeException exception) { ConduitLog.error("system task failed", exception); }
    }, delayMs, periodMs, TimeUnit.MILLISECONDS);
  }
  private final class Builder implements TaskBuilder {
    private final Plugin plugin;
    private final Runnable work;
    private Duration delay = Duration.ZERO;
    private Duration repeat = Duration.ZERO;
    private Builder(Plugin plugin, Runnable work) { this.plugin = plugin; this.work = work; }
    @Override public TaskBuilder delay(Duration delay) { this.delay = delay; return this; }
    @Override public TaskBuilder repeat(Duration interval) { this.repeat = interval; return this; }
    @Override public TaskBuilder async() { return this; }
    @Override public ScheduledTask schedule() {
      boolean once = repeat.isZero() || repeat.isNegative();
      Task task = new Task(plugin);
      Runnable guarded = () -> {
        if (task.cancelled) return;
        try { work.run(); }
        // Not only RuntimeException. An Error thrown by a repeating task was swallowed into its
        // future and silently ended every later run: the plugin's timer just stopped.
        catch (Throwable failure) {
          if (failure instanceof VirtualMachineError fatal) throw fatal;
          ConduitLog.error("plugin task failed: " + plugin.description().id(), failure);
        } finally {
          // A finished one-shot task was never forgotten, so every one a plugin ever ran stayed here.
          if (once) tasks.remove(task);
        }
      };
      long delayMs = Math.max(0, delay.toMillis());
      synchronized (lock) {
        if (retired.contains(plugin)) throw new IllegalStateException("plugin " + plugin.description().id() + " is disabled");
        tasks.add(task);
        try {
          task.future = once
              ? executor.schedule(guarded, delayMs, TimeUnit.MILLISECONDS)
              : executor.scheduleAtFixedRate(guarded, delayMs, Math.max(1, repeat.toMillis()), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException stopped) {
          tasks.remove(task);
          throw new IllegalStateException("the scheduler has shut down", stopped);
        }
      }
      if (task.cancelled) task.future.cancel(false);
      return task;
    }
  }
  private final class Task implements ScheduledTask {
    private final Plugin plugin;
    private volatile ScheduledFuture<?> future;
    private volatile boolean cancelled;
    private Task(Plugin plugin) { this.plugin = plugin; }
    @Override public void cancel() {
      cancelled = true;
      ScheduledFuture<?> scheduled = future;
      if (scheduled != null) scheduled.cancel(false);
      tasks.remove(this);
    }
    @Override public boolean cancelled() { return cancelled; }
  }
}
