// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.security;

import gg.tame.conduit.config.SecuritySettings;
import gg.tame.conduit.config.SecuritySettings.ChannelAction;
import gg.tame.conduit.log.ConduitLog;
import gg.tame.conduit.metrics.ConduitMetrics;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Plugin-message channel policy. Unknown channels allowed unless configured. */
public final class ChannelGuard {
  private volatile SecuritySettings.ChannelGuardSettings settings;
  private final ConcurrentHashMap<String, Long> lastLogNanos = new ConcurrentHashMap<>();
  private static final long LOG_INTERVAL_NANOS = 5_000_000_000L;
  private static final int MAX_LOG_KEYS = 2048;

  public ChannelGuard(SecuritySettings.ChannelGuardSettings settings) {
    this.settings = settings;
  }

  public void applySettings(SecuritySettings.ChannelGuardSettings settings) {
    this.settings = settings;
  }

  public SecuritySettings.ChannelGuardSettings settings() { return settings; }

  public enum Outcome { ALLOW, DROP, KICK }

  public Outcome inspect(String channel, String playerName) {
    if (!settings.enabled()) return Outcome.ALLOW;
    String normalized = SecuritySettings.ChannelGuardSettings.normalizeChannel(channel);
    if (normalized.isBlank()) return Outcome.ALLOW;
    // Never treat brand / forwarding-related channels as exploit channels by default.
    if (normalized.equals("minecraft:brand") || normalized.startsWith("velocity:")) return Outcome.ALLOW;
    ChannelAction action = settings.channels().get(normalized);
    if (action == null) return Outcome.ALLOW;
    if (action == ChannelAction.OFF) return Outcome.ALLOW;
    ConduitMetrics.current().channelGuardAction();
    maybeLog(normalized, playerName, action);
    return switch (action) {
      case LOG -> Outcome.ALLOW;
      case DROP -> Outcome.DROP;
      case KICK -> Outcome.KICK;
      case OFF -> Outcome.ALLOW;
    };
  }

  private void maybeLog(String channel, String playerName, ChannelAction action) {
    if (lastLogNanos.size() > MAX_LOG_KEYS) lastLogNanos.clear();
    long now = System.nanoTime();
    Long previous = lastLogNanos.put(channel, now);
    if (previous != null && now - previous < LOG_INTERVAL_NANOS) return;
    String who = playerName == null || playerName.isBlank() ? "player" : playerName;
    ConduitLog.warn("Channel guard " + action.name().toLowerCase(Locale.ROOT) + " for " + who + " on " + channel);
  }
}
