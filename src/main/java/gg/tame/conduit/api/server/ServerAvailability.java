// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.server;

/** Availability derived from cached status probes — never from a live connect during /server. */
public enum ServerAvailability {
  ONLINE,
  OFFLINE,
  CONNECTING,
  DEGRADED,
  UNKNOWN
}
