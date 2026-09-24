// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.forwarding;

import gg.tame.conduit.config.ForwardingMode;

public interface PlayerInfoForwarder {
  ForwardingMode mode();
  byte[] payload(ForwardingRequest request);
  /** The host a backend handshake carries, Forge marker included; only the legacy modes change it. */
  default String handshakeHost(String host, gg.tame.conduit.login.PlayerProfile player, java.net.InetAddress client) { return host; }
}
