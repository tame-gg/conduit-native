// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.forwarding;

import gg.tame.conduit.config.ForwardingMode;

public interface PlayerInfoForwarder {
  ForwardingMode mode();
  byte[] payload(ForwardingRequest request);
}
