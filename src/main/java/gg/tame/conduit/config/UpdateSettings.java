// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.config;

/**
 * Whether Conduit looks for a newer ViaVersion on start, and what it does when it finds one.
 *
 * <p>The jar bundles a Via known to work with it, so translation needs no network and nothing to
 * fetch. What this governs is the gap after that: Via adds each new Minecraft release well before a
 * Conduit release can, and an operator who wants that should not have to wait for one.
 *
 * <p>These are read by the bootstrap before the proxy's own classes are loaded, because replacing Via
 * means choosing a class path. {@code gg.tame.conduit.boot.BootConfig} reads them from the file on its
 * own; this record is what the rest of Conduit sees, and the two are kept in step by
 * {@code ConfigurationLoader} reading the same keys.
 */
public record UpdateSettings(boolean via, boolean checkOnly, int timeoutMs, int checkIntervalHours) {
  public static final boolean DEFAULT_VIA = true;
  public static final int DEFAULT_TIMEOUT_MS = 5000;
  /**
   * How long a check stands for, in hours. The check is the one thing between starting Conduit and
   * Conduit listening that waits on somebody else's server, and a proxy restarted five times while a
   * config is edited paid for it five times over. 0 checks on every start, as Conduit used to.
   */
  public static final int DEFAULT_CHECK_INTERVAL_HOURS = 12;

  public UpdateSettings {
    // Bounded on both sides: a zero would make every start fail its check, and an unbounded value
    // would let an unreachable repository hold the proxy down instead of merely not updating it.
    if (timeoutMs < 500 || timeoutMs > 60_000) {
      throw new IllegalArgumentException("updates.timeout-ms must be 500..60000");
    }
    // A year is the upper bound, so a typo cannot switch the check off for good while leaving
    // updates.via = true saying it is on.
    if (checkIntervalHours < 0 || checkIntervalHours > 8760) {
      throw new IllegalArgumentException("updates.via-check-interval-hours must be 0..8760");
    }
  }

  public static UpdateSettings defaults() {
    return new UpdateSettings(DEFAULT_VIA, false, DEFAULT_TIMEOUT_MS, DEFAULT_CHECK_INTERVAL_HOURS);
  }
}
