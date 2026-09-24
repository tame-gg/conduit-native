// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.security;

import gg.tame.conduit.config.SecuritySettings;
import gg.tame.conduit.log.ConduitLog;
import gg.tame.conduit.metrics.ConduitMetrics;
import java.net.InetAddress;

/**
 * Runtime-only attack mode. Does not persist across restart.
 * Tightens live throttle/bot thresholds without rewriting config.
 */
public final class AttackModeService {
  private final ConnectionThrottle throttle;
  private final BotFilter botFilter;
  private volatile SecuritySettings.AttackModeSettings settings;
  private volatile boolean active;
  /** Switched on by {@link #recordConnection}, so it may also switch it off; a command's never is. */
  private volatile boolean automatic;
  /** How long the connection rate must stay under the trip line before automatic attack mode lifts. */
  static final long AUTO_LIFT_NANOS = 60_000_000_000L;
  private final Object rate = new Object();
  private long rateSecond = Long.MIN_VALUE;
  private int inSecond;
  private long lastFloodNanos;
  /** ponytail: in memory, so a restart during an attack forgets returning players; seed from addresses.txt if that bites. */
  private final BoundedSourceMap<Boolean> known = new BoundedSourceMap<>(16_384, ignored -> false);

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

  public boolean enable() { return enable(false); }

  private boolean enable(boolean auto) {
    if (active) return false;
    automatic = auto;
    active = true;
    applyLive();
    ConduitMetrics.current().attackModeActivation();
    gg.tame.conduit.ops.Alerts.send("Attack mode engaged: throttle and bot filter tightened.");
    return true;
  }

  public boolean disable() {
    if (!active) return false;
    active = false;
    automatic = false;
    throttle.restoreEffectiveMaxAttempts();
    botFilter.restoreEffectiveThreshold();
    gg.tame.conduit.ops.Alerts.send("Attack mode lifted.");
    return true;
  }

  /**
   * Every accepted socket, before any other check: one lock and two fields, so it costs nothing a
   * flood could use. More than {@code auto-trip-per-second} in one second switches attack mode on;
   * a minute without that switches it back off, if this is what switched it on.
   */
  public void recordConnection(long nanoTime) {
    int limit = settings.autoTripPerSecond();
    if (limit <= 0 && !automatic) return;
    boolean trip;
    boolean lift;
    synchronized (rate) {
      long second = Math.floorDiv(nanoTime, 1_000_000_000L);
      if (second != rateSecond) { rateSecond = second; inSecond = 0; }
      trip = limit > 0 && ++inSecond > limit;
      if (trip) lastFloodNanos = nanoTime;
      lift = !trip && automatic && nanoTime - lastFloodNanos >= AUTO_LIFT_NANOS;
    }
    if (trip && !active && enable(true)) {
      ConduitLog.warn("Attack mode switched on: more than " + limit + " connections in one second.");
    }
    if (lift && automatic && disable()) ConduitLog.info("Attack mode switched off: the connection rate has been normal for a minute.");
  }

  public boolean isAutomatic() { return automatic; }

  /** A source a player finished logging in from, which {@code known-sources-only} lets in during an attack. */
  public void recordLogin(InetAddress address) {
    if (address != null) known.put(key(address), Boolean.TRUE);
  }

  /** False only while attack mode is on with {@code known-sources-only} and nobody has logged in from here. */
  public boolean admitsLogin(InetAddress address) {
    return !active || !settings.knownSourcesOnly() || address == null || known.get(key(address)) != null;
  }

  private SourceKey key(InetAddress address) {
    var grouping = throttle.settings();
    return SourceKey.of(address, grouping.ipv4Prefix(), grouping.ipv6Prefix());
  }

  private void applyLive() {
    throttle.setEffectiveMaxAttempts(settings.throttleMaxAttempts());
    botFilter.setEffectiveThreshold(settings.botStrikeThreshold());
  }

  public SecuritySettings.AttackModeSettings settings() { return settings; }
}
