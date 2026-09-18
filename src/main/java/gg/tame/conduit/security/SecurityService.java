// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.security;

import gg.tame.conduit.config.SecuritySettings;

/** Facade for Phase 2 security subsystems. */
public final class SecurityService {
  private final ConnectionThrottle throttle;
  private final BotFilter botFilter;
  private final ChannelGuard channelGuard;
  private final AttackModeService attackMode;
  private volatile SecuritySettings settings;

  public SecurityService(SecuritySettings settings) {
    this.settings = settings;
    this.throttle = new ConnectionThrottle(settings.throttle());
    this.botFilter = new BotFilter(settings.botFilter());
    this.botFilter.setGrouping(settings.throttle().ipv4Prefix(), settings.throttle().ipv6Prefix());
    this.channelGuard = new ChannelGuard(settings.channelGuard());
    this.attackMode = new AttackModeService(throttle, botFilter, settings.attackMode());
  }

  public synchronized void applySettings(SecuritySettings replacement) {
    this.settings = replacement;
    throttle.applySettings(replacement.throttle());
    botFilter.applySettings(replacement.botFilter());
    botFilter.setGrouping(replacement.throttle().ipv4Prefix(), replacement.throttle().ipv6Prefix());
    channelGuard.applySettings(replacement.channelGuard());
    attackMode.applySettings(replacement.attackMode());
  }

  public SecuritySettings settings() { return settings; }
  public ConnectionThrottle throttle() { return throttle; }
  public BotFilter botFilter() { return botFilter; }
  public ChannelGuard channelGuard() { return channelGuard; }
  public AttackModeService attackMode() { return attackMode; }
}
