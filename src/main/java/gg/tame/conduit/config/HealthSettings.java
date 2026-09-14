package gg.tame.conduit.config;

/** Periodic backend health probe settings with hysteresis thresholds. */
public record HealthSettings(
    boolean enabled,
    int intervalMs,
    int timeoutMs,
    int failureThreshold,
    int successThreshold
) {
  public HealthSettings {
    if (intervalMs < 500) throw new IllegalArgumentException("health.interval-ms must be >= 500");
    if (timeoutMs < 100 || timeoutMs > intervalMs * 2) {
      throw new IllegalArgumentException("health.timeout-ms must be 100.." + (intervalMs * 2));
    }
    if (failureThreshold < 1) throw new IllegalArgumentException("health.failure-threshold must be >= 1");
    if (successThreshold < 1) throw new IllegalArgumentException("health.success-threshold must be >= 1");
  }

  public static HealthSettings defaults() {
    return new HealthSettings(true, 10_000, 1_500, 3, 2);
  }
}
