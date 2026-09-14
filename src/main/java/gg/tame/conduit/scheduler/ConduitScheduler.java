package gg.tame.conduit.scheduler;

import gg.tame.conduit.api.plugin.Plugin;
import gg.tame.conduit.api.scheduler.ScheduledTask;
import gg.tame.conduit.api.scheduler.Scheduler;
import gg.tame.conduit.log.ConduitLog;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Plugin tasks never run on player socket threads. */
public final class ConduitScheduler implements Scheduler, AutoCloseable {
  private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, runnable -> {
    Thread thread = new Thread(runnable, "conduit-scheduler");
    thread.setDaemon(true);
    return thread;
  });
  private final Set<Task> tasks = ConcurrentHashMap.newKeySet();
  @Override public TaskBuilder buildTask(Plugin plugin, Runnable task) { return new Builder(plugin, task); }
  @Override public void cancel(Plugin plugin) {
    for (Task task : new ArrayList<>(tasks)) if (task.plugin == plugin) task.cancel();
  }
  @Override public void close() { executor.shutdownNow(); }

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
      Runnable guarded = () -> {
        try { work.run(); }
        catch (RuntimeException exception) { ConduitLog.error("plugin task failed: " + plugin.description().id(), exception); }
      };
      long delayMs = Math.max(0, delay.toMillis());
      ScheduledFuture<?> future;
      if (repeat.isZero() || repeat.isNegative()) future = executor.schedule(guarded, delayMs, TimeUnit.MILLISECONDS);
      else future = executor.scheduleAtFixedRate(guarded, delayMs, Math.max(1, repeat.toMillis()), TimeUnit.MILLISECONDS);
      Task task = new Task(plugin, future);
      tasks.add(task);
      return task;
    }
  }
  private final class Task implements ScheduledTask {
    private final Plugin plugin;
    private final ScheduledFuture<?> future;
    private Task(Plugin plugin, ScheduledFuture<?> future) { this.plugin = plugin; this.future = future; }
    @Override public void cancel() { future.cancel(false); tasks.remove(this); }
    @Override public boolean cancelled() { return future.isCancelled(); }
  }
}
