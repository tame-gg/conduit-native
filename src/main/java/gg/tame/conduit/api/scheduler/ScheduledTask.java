package gg.tame.conduit.api.scheduler;

public interface ScheduledTask {
  void cancel();
  boolean cancelled();
}
