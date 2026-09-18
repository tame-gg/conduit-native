// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol;

/** Coarse protocol families. Packet IDs still live on ProtocolDefinition. */
public enum ProtocolEra {
  LEGACY,
  CLASSIC_MODERN,
  FLATTENING,
  CONFIGURATION,
  CURRENT
}
