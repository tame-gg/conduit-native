// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol;

/**
 * Completeness of a client↔backend path.
 * PARTIAL must never be reported as FULL.
 */
public enum CompatibilityCompleteness {
  FULL,
  PARTIAL,
  NONE
}
