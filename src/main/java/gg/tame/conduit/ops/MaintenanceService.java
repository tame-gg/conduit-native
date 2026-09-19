// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.ops;

import gg.tame.conduit.command.Permissions;
import gg.tame.conduit.config.MaintenanceSettings;
import gg.tame.conduit.log.ConduitLog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Native maintenance mode. Persists via maintenance.flag beside the config directory.
 */
public final class MaintenanceService {
  private static final String FLAG_FILE = "maintenance.flag";
  private final Path flagPath;
  private final AtomicBoolean active = new AtomicBoolean();
  private volatile MaintenanceSettings settings;
  private volatile boolean featureEnabled;

  public MaintenanceService(Path configDirectory, MaintenanceSettings settings) {
    this.flagPath = configDirectory.resolve(FLAG_FILE);
    this.settings = settings;
    this.featureEnabled = settings.featureEnabled();
    if (!featureEnabled) {
      active.set(false);
      return;
    }
    boolean fromFlag = Files.isRegularFile(flagPath);
    active.set(fromFlag || settings.activeOnStart());
    if (active.get() && !fromFlag) {
      try { persist(true); }
      catch (IOException exception) { ConduitLog.warn("could not write maintenance flag: " + exception.getMessage()); }
    }
  }

  public synchronized void applySettings(MaintenanceSettings replacement) {
    this.settings = replacement;
    this.featureEnabled = replacement.featureEnabled();
    if (!featureEnabled) {
      active.set(false);
      try { Files.deleteIfExists(flagPath); } catch (IOException ignored) { }
    }
  }

  public MaintenanceSettings settings() { return settings; }
  public boolean featureEnabled() { return featureEnabled; }
  public boolean isActive() { return featureEnabled && active.get(); }

  public boolean enable() throws IOException {
    if (!featureEnabled) return false;
    active.set(true);
    persist(true);
    return true;
  }

  public boolean disable() throws IOException {
    active.set(false);
    persist(false);
    return true;
  }

  public boolean allows(String username, boolean hasBypassPermission) {
    if (!isActive()) return true;
    if (hasBypassPermission) return true;
    return settings.allowsUsername(username);
  }

  public boolean allows(gg.tame.conduit.command.CommandSource source) {
    return allows(source.username(), Permissions.allows(source, Permissions.MAINTENANCE_BYPASS));
  }

  public String kickMessage() { return settings.kickMessage(); }
  public String motd() { return settings.motd(); }

  private void persist(boolean enabled) throws IOException {
    if (enabled) {
      Path parent = flagPath.getParent();
      if (parent != null) Files.createDirectories(parent);
      Files.writeString(flagPath, "active\n");
    } else {
      Files.deleteIfExists(flagPath);
    }
  }
}
