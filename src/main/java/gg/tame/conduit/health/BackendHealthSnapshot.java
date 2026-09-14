package gg.tame.conduit.health;

import java.time.Instant;

/** Snapshot of hysteresis counters and routing health for one backend. */
public record BackendHealthSnapshot(
    String name,
    BackendHealth health,
    boolean draining,
    int consecutiveFailures,
    int consecutiveSuccesses,
    Instant lastProbe,
    boolean lastProbeOk
) {
  public boolean routable() {
    return health == BackendHealth.HEALTHY && !draining;
  }
}
