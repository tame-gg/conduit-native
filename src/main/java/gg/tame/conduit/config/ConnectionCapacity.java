// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.config;

import java.util.OptionalLong;

/**
 * How many connections this machine can actually hold, for a {@code listener.max-connections} the
 * operator has not set.
 *
 * <p>Velocity has no player limit at all: the number in the server list is decoration, and nothing
 * behind it refuses anyone. It can afford that because an idle connection costs it a socket and a
 * buffer. Conduit is almost there -- since the selector relay a playing session costs no thread
 * either -- but the login phase is still a thread per connection, virtual on Linux and platform on
 * Windows (JDK-8334574), so something has to bound how many logins may be in flight at once. The
 * per-source throttle, the bot filter and the handshake timeout do most of that; this is the last
 * backstop, the one that stops the accept loop taking a connection the operating system cannot give
 * it a descriptor for.
 *
 * <p>So the limit is not a player policy and should not be one: a number an operator has to guess is
 * a number they guess wrong, and the failure it guards against is a property of the machine rather
 * than of the network. Left unset it is read off the machine -- two descriptors per player, some
 * held back for the listener, the backend health checks and whatever the JVM has open -- and an
 * operator who wants a policy on top of that writes a smaller number and gets exactly it.
 */
public final class ConnectionCapacity {
  /**
   * Descriptors held back for everything that is not a player: the listener, backend health checks,
   * the jar's own open files, Via's, a plugin's database pool. Generous on purpose -- being wrong
   * here costs a few possible players, and being wrong the other way costs the accept loop.
   */
  private static final int RESERVED_DESCRIPTORS = 256;
  /** Two per player: the client's socket and the backend's. */
  private static final int DESCRIPTORS_PER_CONNECTION = 2;
  /**
   * What is used where the descriptor limit cannot be read, which is Windows. Handles are not the
   * scarce thing there and there is no ulimit to consult, so this is a plain conservative number
   * rather than a measurement: far above what any single instance is asked to hold in practice, and
   * far below where a flood of logins would matter.
   */
  static final int UNKNOWN_PLATFORM_DEFAULT = 10_000;
  /**
   * The least this will derive. A machine whose descriptor limit is the old default of 1024 would
   * otherwise be handed a cap of a few hundred, which is a worse surprise than running slightly past
   * what its ulimit likes: the accept failure is loud and recoverable, and Conduit says at start
   * that the limit is short.
   */
  static final int FLOOR = 2048;

  private ConnectionCapacity() { }

  /** What {@code listener.max-connections} is when the file does not say. */
  public static int automatic() {
    OptionalLong descriptors = descriptorLimit();
    if (descriptors.isEmpty()) return UNKNOWN_PLATFORM_DEFAULT;
    long usable = (descriptors.getAsLong() - RESERVED_DESCRIPTORS) / DESCRIPTORS_PER_CONNECTION;
    long clamped = Math.min(ConduitConfiguration.MAX_MAX_CONNECTIONS, Math.max(FLOOR, usable));
    return (int) clamped;
  }

  /** Whether {@link #automatic} read the machine or fell back, so a start can say which. */
  public static boolean derivedFromMachine() { return descriptorLimit().isPresent(); }

  /**
   * What the operating system will let this process open, when it will say. Absent on Windows and on
   * any JDK without the Unix bean, where nothing is claimed rather than a wrong number reported.
   */
  public static OptionalLong descriptorLimit() {
    try {
      var bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
      if (bean instanceof com.sun.management.UnixOperatingSystemMXBean unix) {
        long max = unix.getMaxFileDescriptorCount();
        return max > 0 ? OptionalLong.of(max) : OptionalLong.empty();
      }
    } catch (RuntimeException | LinkageError unavailable) {
      // A JDK without com.sun.management, or one that refuses the bean. Not worth a line.
    }
    return OptionalLong.empty();
  }
}
