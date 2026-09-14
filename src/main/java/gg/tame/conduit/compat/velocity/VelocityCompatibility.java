package gg.tame.conduit.compat.velocity;

/**
 * Velocity compatibility is an isolated adapter over the native Conduit API.
 * Conduit core does not depend on Velocity types.
 *
 * <p>Status: PARTIAL — NOT YET VERIFIED against real Velocity-compiled plugins.
 *
 * <pre>
 * Supported (native equivalents):
 *   player lookup, server lookup, connect, messages, commands, events, scheduler, permissions
 * Partial:
 *   plugin containers, plugin messaging channels, Adventure components (plain text only)
 * Unsupported:
 *   Velocity internals, Scoreboard, BossBar, ResourcePackInfo, EventTask continuations
 * </pre>
 */
public final class VelocityCompatibility {
  public static final String STATUS = "PARTIAL — NOT YET VERIFIED";
  private VelocityCompatibility() {}
}
