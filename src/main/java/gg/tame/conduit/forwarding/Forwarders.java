// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.forwarding;

import gg.tame.conduit.config.ConduitConfiguration;
import java.io.IOException;

/** The forwarder for the configured mode; the secret is read here, at start, for the modes that use it. */
public final class Forwarders {
  private Forwarders() { }
  public static PlayerInfoForwarder create(ConduitConfiguration configuration) throws IOException {
    return switch (configuration.forwardingMode()) {
      case NONE -> new NoneForwarder();
      case MODERN -> new ModernForwarder(ForwardingSecret.load(configuration.forwardingSecretFile().orElseThrow()));
      case LEGACY -> LegacyForwarder.legacy();
      case BUNGEEGUARD -> LegacyForwarder.bungeeGuard(ForwardingSecret.load(configuration.forwardingSecretFile().orElseThrow()));
    };
  }
}
