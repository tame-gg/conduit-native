// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.forwarding;

import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.config.ForwardingMode;
import java.io.IOException;

/** Configures only modes with a concrete implementation; unsupported modes never silently degrade. */
public final class Forwarders {
  private Forwarders() { }
  public static PlayerInfoForwarder create(ConduitConfiguration configuration) throws IOException {
    if (configuration.forwardingMode() == ForwardingMode.NONE) return new NoneForwarder();
    if (configuration.forwardingMode() == ForwardingMode.MODERN) return new ModernForwarder(ForwardingSecret.load(configuration.forwardingSecretFile().orElseThrow()));
    throw new UnsupportedOperationException("forwarding mode " + configuration.forwardingMode().name().toLowerCase() + " is not implemented");
  }
}
