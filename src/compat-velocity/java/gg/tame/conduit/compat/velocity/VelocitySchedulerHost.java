package gg.tame.conduit.compat.velocity;

import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;
import com.velocitypowered.api.scheduler.TaskStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Velocity's Scheduler on Conduit's: Conduit's scheduler keeps the time and owns the task (so it
 * dies with the plugin), and the body runs on the adapter's threads. A repeating task whose last
 * run has not finished skips a beat rather than running twice at once.
 */
final class VelocitySchedulerHost implements Scheduler {
  private final VelocityEnvironment environment;
  private final Set<Task> tasks = ConcurrentHashMap.newKeySet();
  VelocitySchedulerHost(VelocityEnvironment environment) { this.environment = environment; }

  @Override public TaskBuilder buildTask(Object plugin, Runnable runnable) {
    if (runnable == null) throw new IllegalArgumentException("task is required");
    return new Builder(environment.plugins.require(plugin), task -> runnable.run());
  }
  @Override public TaskBuilder buildTask(Object plugin, Consumer<ScheduledTask> consumer) {
    if (consumer == null) throw new IllegalArgumentException("task is required");
    return new Builder(environment.plugins.require(plugin), consumer);
  }
  @Override public Collection<ScheduledTask> tasksByPlugin(Object plugin) {
    VelocityPluginHost.Container container = environment.plugins.require(plugin);
    List<ScheduledTask> owned = new ArrayList<>();
    for (Task task : tasks) if (task.plugin == container) owned.add(task);
    return List.copyOf(owned);
  }
  /** Conduit has already cancelled the native tasks; this marks ours to match. */
  void forget(VelocityPluginHost.Container plugin) {
    for (Task task : tasks) if (task.plugin == plugin) task.cancel();
  }

  private final class Builder implements TaskBuilder {
    private final VelocityPluginHost.Container plugin;
    private final Consumer<ScheduledTask> body;
    private long delayMillis;
    private long repeatMillis;
    private Builder(VelocityPluginHost.Container plugin, Consumer<ScheduledTask> body) { this.plugin = plugin; this.body = body; }
    @Override public TaskBuilder delay(long time, TimeUnit unit) { delayMillis = Math.max(0, unit.toMillis(time)); return this; }
    @Override public TaskBuilder repeat(long time, TimeUnit unit) { repeatMillis = Math.max(0, unit.toMillis(time)); return this; }
    @Override public TaskBuilder clearDelay() { delayMillis = 0; return this; }
    @Override public TaskBuilder clearRepeat() { repeatMillis = 0; return this; }
    @Override public ScheduledTask schedule() {
      Task task = new Task(plugin, body, repeatMillis > 0);
      tasks.add(task);
      var builder = environment.conduit.scheduler().buildTask(plugin.handle, task::due).delay(Duration.ofMillis(delayMillis));
      if (repeatMillis > 0) builder = builder.repeat(Duration.ofMillis(repeatMillis));
      task.attach(builder.schedule());
      return task;
    }
  }

  private final class Task implements ScheduledTask {
    private final VelocityPluginHost.Container plugin;
    private final Consumer<ScheduledTask> body;
    private final boolean repeating;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile gg.tame.conduit.api.scheduler.ScheduledTask timer;
    private volatile TaskStatus status = TaskStatus.SCHEDULED;
    private Task(VelocityPluginHost.Container plugin, Consumer<ScheduledTask> body, boolean repeating) {
      this.plugin = plugin; this.body = body; this.repeating = repeating;
    }
    /** A task that cancelled itself before its timer was handed over still stops the timer. */
    private void attach(gg.tame.conduit.api.scheduler.ScheduledTask timer) {
      this.timer = timer;
      if (status == TaskStatus.CANCELLED) timer.cancel();
    }
    private void due() {
      if (status != TaskStatus.SCHEDULED || !running.compareAndSet(false, true)) return;
      environment.work.execute(() -> {
        try { if (status == TaskStatus.SCHEDULED) body.accept(this); }
        catch (Throwable failed) { environment.log.log(Level.SEVERE, "Velocity plugin " + plugin.id() + " task failed", failed); }
        finally {
          running.set(false);
          if (!repeating && status == TaskStatus.SCHEDULED) {
            status = TaskStatus.FINISHED;
            tasks.remove(this);
          }
        }
      });
    }
    @Override public Object plugin() { return plugin.instance; }
    @Override public TaskStatus status() { return status; }
    @Override public void cancel() {
      if (status == TaskStatus.SCHEDULED) status = TaskStatus.CANCELLED;
      var current = timer;
      if (current != null) current.cancel();
      tasks.remove(this);
    }
  }
}
