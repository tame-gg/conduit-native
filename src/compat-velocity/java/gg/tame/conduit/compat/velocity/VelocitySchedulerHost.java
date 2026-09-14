package gg.tame.conduit.compat.velocity;

import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;
import com.velocitypowered.api.scheduler.TaskStatus;
import gg.tame.conduit.log.ConduitLog;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class VelocitySchedulerHost implements Scheduler {
  private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, runnable -> {
    Thread thread = new Thread(runnable, "conduit-velocity-scheduler");
    thread.setDaemon(true);
    return thread;
  });
  private final Set<Task> tasks = ConcurrentHashMap.newKeySet();
  @Override public TaskBuilder buildTask(Object plugin, Runnable runnable) {
    return new Builder(plugin, task -> runnable.run());
  }
  @Override public TaskBuilder buildTask(Object plugin, Consumer<ScheduledTask> consumer) {
    return new Builder(plugin, consumer);
  }
  @Override public Collection<ScheduledTask> tasksByPlugin(Object plugin) {
    List<ScheduledTask> result = new ArrayList<>();
    for (Task task : tasks) if (task.plugin == plugin) result.add(task);
    return result;
  }
  void shutdown() {
    for (Task task : new ArrayList<>(tasks)) task.cancel();
    executor.shutdownNow();
  }
  void cancelPlugin(Object plugin) {
    for (Task task : new ArrayList<>(tasks)) if (task.plugin == plugin) task.cancel();
  }
  private final class Builder implements TaskBuilder {
    private final Object plugin;
    private final Consumer<ScheduledTask> work;
    private long delayMs;
    private long repeatMs;
    private Builder(Object plugin, Consumer<ScheduledTask> work) { this.plugin = plugin; this.work = work; }
    @Override public TaskBuilder delay(long time, TimeUnit unit) { delayMs = unit.toMillis(time); return this; }
    @Override public TaskBuilder repeat(long time, TimeUnit unit) { repeatMs = unit.toMillis(time); return this; }
    @Override public TaskBuilder clearDelay() { delayMs = 0; return this; }
    @Override public TaskBuilder clearRepeat() { repeatMs = 0; return this; }
    @Override public ScheduledTask schedule() {
      Task task = new Task(plugin);
      Runnable guarded = () -> {
        try { work.accept(task); }
        catch (RuntimeException exception) { ConduitLog.error("Velocity plugin task failed", exception); }
      };
      ScheduledFuture<?> future;
      if (repeatMs > 0) future = executor.scheduleAtFixedRate(guarded, delayMs, repeatMs, TimeUnit.MILLISECONDS);
      else future = executor.schedule(guarded, delayMs, TimeUnit.MILLISECONDS);
      task.future = future;
      tasks.add(task);
      return task;
    }
  }
  private final class Task implements ScheduledTask {
    private final Object plugin;
    private ScheduledFuture<?> future;
    private Task(Object plugin) { this.plugin = plugin; }
    @Override public Object plugin() { return plugin; }
    @Override public TaskStatus status() {
      if (future == null) return TaskStatus.SCHEDULED;
      if (future.isCancelled()) return TaskStatus.CANCELLED;
      if (future.isDone()) return TaskStatus.FINISHED;
      return TaskStatus.SCHEDULED;
    }
    @Override public void cancel() {
      if (future != null) future.cancel(false);
      tasks.remove(this);
    }
  }
}
