package gg.tame.conduit.scheduler;

import gg.tame.conduit.api.plugin.Plugin;
import gg.tame.conduit.api.scheduler.ScheduledTask;
import gg.tame.conduit.api.scheduler.Scheduler;
import gg.tame.conduit.log.ConduitLog;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plugin tasks never run on player socket threads, nor on another plugin's.
 *
 * <p>One timer thread only keeps time: when a task is due it hands the run to that plugin's own
 * threads. All plugins used to share two threads, so one plugin's task blocking on a slow database
 * or a socket held back every other plugin's timers for as long as it blocked. Each plugin's pool is
 * cached, of platform threads -- a task may block on a socket, and on Windows that must not happen
 * on a virtual thread (see SocketThreads) -- made on its first task and shut down when it is retired.
 */
public final class ConduitScheduler implements Scheduler, AutoCloseable {
  private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
    Thread thread = new Thread(runnable, "conduit-scheduler");
    thread.setDaemon(true);
    return thread;
  });
  private final Set<Task> tasks = ConcurrentHashMap.newKeySet();
  /** Guarded by {@code lock}. Only ever created for a plugin that is not retired. */
  private final Map<Plugin, ExecutorService> pools = new HashMap<>();
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
  /** Cancels the plugin's tasks, refuses it any new ones, and lets its threads go. */
  public void retire(Plugin plugin) {
    ExecutorService pool;
    synchronized (lock) {
      retired.add(plugin);
      pool = pools.remove(plugin);
    }
    cancel(plugin);
    // Not shutdownNow: a task still running finishes rather than being interrupted mid-write, and
    // its threads end once it has. Whatever it tries to schedule on the way out is refused above.
    if (pool != null) pool.shutdown();
  }
  @Override public void close() {
    timer.shutdownNow();
    synchronized (lock) {
      for (ExecutorService pool : pools.values()) pool.shutdownNow();
      pools.clear();
    }
  }
  /** Live tasks, for tests: a finished one-shot task must not stay here. */
  public int taskCount() { return tasks.size(); }
  /** Called holding {@code lock}. */
  private ExecutorService poolFor(Plugin plugin) {
    return pools.computeIfAbsent(plugin, owner -> Executors.newCachedThreadPool(
        Thread.ofPlatform().name("conduit-plugin-" + owner.description().id() + "-", 0).daemon(true).factory()));
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
        try {
          if (task.cancelled) return;
          work.run();
        }
        // Not only RuntimeException. An Error thrown by a repeating task was swallowed into its
        // future and silently ended every later run: the plugin's timer just stopped.
        catch (Throwable failure) {
          if (failure instanceof VirtualMachineError fatal) throw fatal;
          ConduitLog.error("plugin task failed: " + plugin.description().id(), failure);
        } finally {
          task.running.set(false);
          // A finished one-shot task was never forgotten, so every one a plugin ever ran stayed here.
          if (once) tasks.remove(task);
        }
      };
      long delayMs = Math.max(0, delay.toMillis());
      synchronized (lock) {
        if (retired.contains(plugin)) throw new IllegalStateException("plugin " + plugin.description().id() + " is disabled");
        ExecutorService pool = poolFor(plugin);
        // The timer only hands the run over. A repeating run still going when its next one falls due
        // skips that one, so a task never overlaps itself even though it no longer runs on the
        // thread that times it.
        Runnable dispatch = () -> {
          if (task.cancelled || !task.running.compareAndSet(false, true)) return;
          try { pool.execute(guarded); }
          catch (RejectedExecutionException retiredMeanwhile) {
            task.running.set(false);
            tasks.remove(task);
          }
        };
        tasks.add(task);
        try {
          task.future = once
              ? timer.schedule(dispatch, delayMs, TimeUnit.MILLISECONDS)
              : timer.scheduleAtFixedRate(dispatch, delayMs, Math.max(1, repeat.toMillis()), TimeUnit.MILLISECONDS);
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
    private final AtomicBoolean running = new AtomicBoolean();
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
