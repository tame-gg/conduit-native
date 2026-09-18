// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.forwarding;

import gg.tame.conduit.config.ForwardingMode;

public final class NoneForwarder implements PlayerInfoForwarder {
  @Override public ForwardingMode mode() { return ForwardingMode.NONE; }
  @Override public byte[] payload(ForwardingRequest request) { return new byte[0]; }
}
