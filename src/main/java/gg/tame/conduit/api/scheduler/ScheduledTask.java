// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.scheduler;

public interface ScheduledTask {
  void cancel();
  boolean cancelled();
}
