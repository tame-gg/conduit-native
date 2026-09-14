package gg.tame.conduit.api.plugin;

import gg.tame.conduit.api.ConduitProxy;
import gg.tame.conduit.api.scheduler.Scheduler;
import java.nio.file.Path;
import java.util.logging.Logger;

public interface Plugin {
  PluginDescription description();
  ConduitProxy proxy();
  Logger getLogger();
  Path dataDirectory();
  Scheduler getScheduler();
  void onLoad();
  void onEnable();
  void onDisable();
}
