package gg.tame.conduit.security;

import gg.tame.conduit.config.SecuritySettings;
import gg.tame.conduit.log.ConduitLog;
import gg.tame.conduit.metrics.ConduitMetrics;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cheap per-source accept throttle. Status/login classification happens after admit.
 * Aggregate logs only — never one line per drop.
 */
public final class ConnectionThrottle {
  private static final int MAX_SOURCES = 8192;
  private volatile SecuritySettings.ThrottleSettings settings;
  private volatile int effectiveMaxAttempts;
  private final BoundedSourceMap<Window> windows = new BoundedSourceMap<>(MAX_SOURCES, window -> window.concurrent.get() > 0);

  public ConnectionThrottle(SecuritySettings.ThrottleSettings settings) {
    applySettings(settings);
  }

  public synchronized void applySettings(SecuritySettings.ThrottleSettings settings) {
    this.settings = settings;
    this.effectiveMaxAttempts = settings.maxAttempts();
  }

  public void setEffectiveMaxAttempts(int maxAttempts) {
    this.effectiveMaxAttempts = Math.max(1, maxAttempts);
  }

  public void restoreEffectiveMaxAttempts() {
    this.effectiveMaxAttempts = settings.maxAttempts();
  }

  public SecuritySettings.ThrottleSettings settings() { return settings; }
  public int effectiveMaxAttempts() { return effectiveMaxAttempts; }

  public enum Decision { ALLOW, THROTTLED, DISABLED }

  /** Carries the counter it was granted from, so releasing never depends on a second lookup. */
  public record Lease(SourceKey key, boolean held, AtomicInteger concurrent) {
    static Lease none() { return new Lease(null, false, null); }
  }

  public Decision tryAdmit(InetAddress address, LeaseHolder holder) {
    if (!settings.enabled()) {
      holder.lease = Lease.none();
      return Decision.DISABLED;
    }
    SourceKey key = SourceKey.of(address, settings.ipv4Prefix(), settings.ipv6Prefix());
    Window window = windows.compute(key, ignored -> new Window());
    long now = System.nanoTime();
    synchronized (window) {
      maybeRotate(window, now);
      if (window.concurrent.get() >= settings.maxConcurrent()) {
        reject(window, now);
        holder.lease = Lease.none();
        return Decision.THROTTLED;
      }
      if (window.attempts >= effectiveMaxAttempts) {
        reject(window, now);
        holder.lease = Lease.none();
        return Decision.THROTTLED;
      }
      window.attempts++;
      window.concurrent.incrementAndGet();
      holder.lease = new Lease(key, true, window.concurrent);
      ConduitMetrics.current().connectionAccepted();
      return Decision.ALLOW;
    }
  }

  public void release(Lease lease) {
    if (lease == null || !lease.held() || lease.concurrent() == null) return;
    lease.concurrent().updateAndGet(value -> Math.max(0, value - 1));
  }

  /** Connections admitted and not yet released, across every source still being tracked. */
  public int inFlight() {
    int[] total = {0};
    windows.forEachValue(window -> total[0] += window.concurrent.get());
    return total[0];
  }

  private void reject(Window window, long now) {
    ConduitMetrics.current().connectionThrottled();
    window.drops++;
    long intervalNanos = settings.logIntervalMs() * 1_000_000L;
    if (now - window.lastLogNanos >= intervalNanos) {
      ConduitLog.warn("Throttled " + window.drops + " connection(s) from a source in the last window.");
      window.drops = 0;
      window.lastLogNanos = now;
    }
  }

  private void maybeRotate(Window window, long now) {
    long windowNanos = settings.windowMs() * 1_000_000L;
    if (now - window.windowStartNanos >= windowNanos) {
      window.windowStartNanos = now;
      window.attempts = 0;
    }
  }

  /** Mutable holder so callers can release on finally without changing method signatures awkwardly. */
  public static final class LeaseHolder {
    public Lease lease = Lease.none();
  }

  private static final class Window {
    private long windowStartNanos = System.nanoTime();
    private int attempts;
    private int drops;
    private long lastLogNanos;
    private final AtomicInteger concurrent = new AtomicInteger();
  }
}
