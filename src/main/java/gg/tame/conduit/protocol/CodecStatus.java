// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol;

/**
 * How much Conduit actually knows about one protocol's wire format.
 *
 * <p>Deliberately separate from {@link TranslationSupport} and
 * {@link CompatibilityCompleteness}: a protocol can have a complete, verified
 * codec and still have no translator to any other version, and a protocol can
 * have an authored codec that no real client has ever exercised. Collapsing
 * these into one flag is how a proxy ends up claiming support it does not have.
 */
public enum CodecStatus {
  /** No packet table at all. Conduit cannot speak this protocol. */
  NONE,

  /**
   * The table was inherited wholesale from another protocol's table plus an
   * explicit delta. Every mapping traces to authored data, but this exact
   * protocol number has not been audited packet-by-packet against its own
   * release. Usable; not a support claim.
   */
  DERIVED,

  /**
   * The table was authored directly for this protocol from published packet-id
   * data. Not yet exercised against a real client or server.
   */
  DECLARED,

  /**
   * Authored and exercised end-to-end against a real Minecraft client and/or
   * server of this version. Only a real wire test may promote a protocol here.
   */
  VERIFIED;

  /** Whether Conduit has a usable packet table, regardless of validation depth. */
  public boolean usable() {
    return this != NONE;
  }
}
