package gg.tame.conduit.health;

/** Routing-oriented health derived from hysteresis of cached probes. */
public enum BackendHealth {
  HEALTHY,
  UNHEALTHY,
  UNKNOWN
}
