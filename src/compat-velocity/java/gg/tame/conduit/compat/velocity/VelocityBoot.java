// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.compat.velocity;

import gg.tame.conduit.api.ConduitProxy;

/**
 * Entry point. Conduit finds this class by name when it is on the class path and hands it the
 * native API; the adapter never sees anything else of Conduit.
 */
public final class VelocityBoot {
  private VelocityBoot() {}
  public static void install(ConduitProxy proxy) {
    VelocityEnvironment environment = new VelocityEnvironment(proxy);
    proxy.events().register(environment.owner, new VelocityEventBridge(environment));
    proxy.plugins().registerLoader(new VelocityPluginLoader(environment));
  }
}
