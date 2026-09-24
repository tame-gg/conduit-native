// SPDX-License-Identifier: GPL-3.0-or-later
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
  /** A key-value store kept in this plugin's data directory between restarts. */
  default PluginStore store() { return PluginStore.in(dataDirectory()); }
  Scheduler getScheduler();
  void onLoad();
  void onEnable();
  void onDisable();
}
