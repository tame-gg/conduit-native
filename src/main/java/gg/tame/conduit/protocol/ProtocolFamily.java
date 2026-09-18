// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol;

/**
 * Organizational protocol families for the modern compatibility program (1.13–26.2).
 * Packet definitions remain authoritative — families do not imply identical wires.
 */
public enum ProtocolFamily {
  LEGACY_OUT_OF_SCOPE,
  V1_13,
  V1_14,
  V1_15,
  V1_16,
  V1_17,
  V1_18,
  V1_19,
  V1_20,
  V1_21,
  V26,
  UNKNOWN;

  public static ProtocolFamily ofProtocol(int number) {
    if (number < 393) return LEGACY_OUT_OF_SCOPE;
    if (number <= 404) return V1_13;
    if (number <= 498) return V1_14;
    if (number <= 578) return V1_15;
    if (number <= 754) return V1_16;
    if (number <= 756) return V1_17;
    if (number <= 758) return V1_18;
    if (number <= 762) return V1_19;
    if (number <= 766) return V1_20;
    if (number <= 774) return V1_21;
    if (number <= 776) return V26;
    return UNKNOWN;
  }

  public boolean isModernProgram() {
    return this != LEGACY_OUT_OF_SCOPE && this != UNKNOWN;
  }
}
