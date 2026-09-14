package gg.tame.conduit.compat.velocity;

/**
 * Velocity compatibility is an isolated adapter over the native Conduit API.
 * Conduit core does not depend on Velocity types at compile time; the adapter
 * lives in {@code src/compat-velocity} and is loaded reflectively.
 *
 * <p>Status: PARTIAL — verified with an in-repo plugin compiled against
 * {@code com.velocitypowered:velocity-api}. Not verified with LuckPerms,
 * ViaVersion, or other production Velocity plugins.
 *
 * <pre>
 * Supported (native equivalents):
 *   player lookup, server lookup, connect, messages, SimpleCommand, events, scheduler
 * Partial:
 *   plugin containers, channel registrar, Adventure (plain text), ProxyConfig
 * Unsupported:
 *   Velocity internals, BrigadierCommand, EventTask, scoreboard, boss bar, resource packs
 * </pre>
 *
 * @see docs/VELOCITY_COMPATIBILITY.md
 */
public final class VelocityCompatibility {
  public static final String STATUS = "PARTIAL — verified with in-repo Velocity-API plugin";
  private VelocityCompatibility() {}
}
