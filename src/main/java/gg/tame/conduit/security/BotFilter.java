package gg.tame.conduit.security;

import gg.tame.conduit.config.SecuritySettings;
import gg.tame.conduit.log.ConduitLog;
import gg.tame.conduit.metrics.ConduitMetrics;
import java.net.InetAddress;

/**
 * Tracks sources that repeatedly open TCP without speaking Minecraft.
 * Status/list pings are legitimate and clear provisional strikes.
 */
public final class BotFilter {
  private static final int MAX_SOURCES = 8192;
  private volatile SecuritySettings.BotFilterSettings settings;
  private volatile int effectiveThreshold;
  private volatile int ipv4Prefix = 32;
  private volatile int ipv6Prefix = 64;
  private final BoundedSourceMap<SourceState> states = new BoundedSourceMap<>(MAX_SOURCES, ignored -> false);

  public BotFilter(SecuritySettings.BotFilterSettings settings) {
    applySettings(settings);
  }

  public synchronized void applySettings(SecuritySettings.BotFilterSettings settings) {
    this.settings = settings;
    this.effectiveThreshold = settings.strikeThreshold();
  }

  public void setEffectiveThreshold(int threshold) {
    this.effectiveThreshold = Math.max(1, threshold);
  }

  public void restoreEffectiveThreshold() {
    this.effectiveThreshold = settings.strikeThreshold();
  }

  public SecuritySettings.BotFilterSettings settings() { return settings; }
  public int effectiveThreshold() { return effectiveThreshold; }

  public void setGrouping(int ipv4Prefix, int ipv6Prefix) {
    this.ipv4Prefix = ipv4Prefix;
    this.ipv6Prefix = ipv6Prefix;
  }

  public boolean isBlocked(InetAddress address) {
    if (!settings.enabled()) return false;
    SourceKey key = SourceKey.of(address, ipv4Prefix, ipv6Prefix);
    SourceState state = states.get(key);
    if (state == null) return false;
    long now = System.nanoTime();
    synchronized (state) {
      if (state.blockedUntilNanos == 0) return false;
      if (now >= state.blockedUntilNanos) {
        state.blockedUntilNanos = 0;
        state.strikes = 0;
        return false;
      }
      return true;
    }
  }

  public void recordValidHandshake(InetAddress address) {
    if (!settings.enabled()) return;
    SourceKey key = SourceKey.of(address, ipv4Prefix, ipv6Prefix);
    SourceState state = states.compute(key, ignored -> new SourceState());
    synchronized (state) {
      state.strikes = Math.max(0, state.strikes - 1);
      state.lastGoodNanos = System.nanoTime();
    }
  }

  public void recordStatusPing(InetAddress address) {
    recordValidHandshake(address);
  }

  public void recordSuspicious(InetAddress address, String reason) {
    if (!settings.enabled()) return;
    SourceKey key = SourceKey.of(address, ipv4Prefix, ipv6Prefix);
    SourceState state = states.compute(key, ignored -> new SourceState());
    long now = System.nanoTime();
    synchronized (state) {
      expireStrikes(state, now);
      state.strikes++;
      state.lastStrikeNanos = now;
      ConduitMetrics.current().botFilterStrike();
      if (state.strikes >= effectiveThreshold) {
        state.blockedUntilNanos = now + settings.blockDurationMs() * 1_000_000L;
        state.strikes = 0;
        ConduitMetrics.current().botFilterBlock();
        ConduitLog.warn("Temporarily blocked a source after repeated non-Minecraft connections (" + reason + ").");
      }
    }
  }

  public void unblock(InetAddress address) {
    SourceKey key = SourceKey.of(address, ipv4Prefix, ipv6Prefix);
    SourceState state = states.get(key);
    if (state == null) return;
    synchronized (state) {
      state.blockedUntilNanos = 0;
      state.strikes = 0;
    }
  }

  public int trackedSources() { return states.size(); }

  private void expireStrikes(SourceState state, long now) {
    long window = settings.strikeWindowMs() * 1_000_000L;
    if (state.lastStrikeNanos != 0 && now - state.lastStrikeNanos > window) {
      state.strikes = 0;
    }
  }

  private static final class SourceState {
    private int strikes;
    private long blockedUntilNanos;
    private long lastStrikeNanos;
    private long lastGoodNanos;
  }
}
