// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.security;

import gg.tame.conduit.config.SecuritySettings;
import gg.tame.conduit.metrics.ConduitMetrics;

/**
 * Runtime-only attack mode. Does not persist across restart.
 * Tightens live throttle/bot thresholds without rewriting config.
 */
public final class AttackModeService {
  private final ConnectionThrottle throttle;
  private final BotFilter botFilter;
  private volatile SecuritySettings.AttackModeSettings settings;
  private volatile boolean active;

  public AttackModeService(ConnectionThrottle throttle, BotFilter botFilter, SecuritySettings.AttackModeSettings settings) {
    this.throttle = throttle;
    this.botFilter = botFilter;
    this.settings = settings;
  }

  public void applySettings(SecuritySettings.AttackModeSettings settings) {
    this.settings = settings;
    if (active) applyLive();
  }

  public boolean isActive() { return active; }

  public boolean enable() {
    if (active) return false;
    active = true;
    applyLive();
    ConduitMetrics.current().attackModeActivation();
    gg.tame.conduit.ops.Alerts.send("Attack mode engaged: throttle and bot filter tightened.");
    return true;
  }

  public boolean disable() {
    if (!active) return false;
    active = false;
    throttle.restoreEffectiveMaxAttempts();
    botFilter.restoreEffectiveThreshold();
    gg.tame.conduit.ops.Alerts.send("Attack mode lifted.");
    return true;
  }

  private void applyLive() {
    throttle.setEffectiveMaxAttempts(settings.throttleMaxAttempts());
    botFilter.setEffectiveThreshold(settings.botStrikeThreshold());
  }

  public SecuritySettings.AttackModeSettings settings() { return settings; }
}
