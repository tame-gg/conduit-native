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
public record UpdateSettings(boolean via, boolean checkOnly, int timeoutMs) {
  public static final boolean DEFAULT_VIA = true;
  public static final int DEFAULT_TIMEOUT_MS = 5000;

  public UpdateSettings {
    // Bounded on both sides: a zero would make every start fail its check, and an unbounded value
    // would let an unreachable repository hold the proxy down instead of merely not updating it.
    if (timeoutMs < 500 || timeoutMs > 60_000) {
      throw new IllegalArgumentException("updates.timeout-ms must be 500..60000");
    }
  }

  public static UpdateSettings defaults() {
    return new UpdateSettings(DEFAULT_VIA, false, DEFAULT_TIMEOUT_MS);
  }
}
