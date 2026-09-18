// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.plugin;

import gg.tame.conduit.api.ConduitProxy;
import gg.tame.conduit.api.scheduler.Scheduler;
import java.nio.file.Path;
import java.util.logging.Logger;

public abstract class ConduitPlugin implements Plugin {
  private PluginDescription description;
  private ConduitProxy proxy;
  private Logger logger;
  private Path dataDirectory;
  private Scheduler scheduler;
  public final void attach(PluginDescription description, ConduitProxy proxy, Logger logger, Path dataDirectory, Scheduler scheduler) {
    this.description = description;
    this.proxy = proxy;
    this.logger = logger;
    this.dataDirectory = dataDirectory;
    this.scheduler = scheduler;
  }
  @Override public PluginDescription description() { return description; }
  @Override public ConduitProxy proxy() { return proxy; }
  @Override public Logger getLogger() { return logger; }
  @Override public Path dataDirectory() { return dataDirectory; }
  @Override public Scheduler getScheduler() { return scheduler; }
  @Override public void onLoad() {}
  @Override public void onEnable() {}
  @Override public void onDisable() {}
}
