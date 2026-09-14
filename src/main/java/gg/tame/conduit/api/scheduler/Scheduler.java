package gg.tame.conduit.api.scheduler;

import gg.tame.conduit.api.plugin.Plugin;
import java.time.Duration;

public interface Scheduler {
  TaskBuilder buildTask(Plugin plugin, Runnable task);
  void cancel(Plugin plugin);

  interface TaskBuilder {
    TaskBuilder delay(Duration delay);
    TaskBuilder repeat(Duration interval);
    TaskBuilder async();
    ScheduledTask schedule();
  }
}
