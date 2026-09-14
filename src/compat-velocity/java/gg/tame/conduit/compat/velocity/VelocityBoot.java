package gg.tame.conduit.compat.velocity;

import gg.tame.conduit.runtime.ConduitRuntime;
import gg.tame.conduit.plugin.ConduitPluginManager;

/** Entry point loaded by reflection so Conduit core does not import Velocity types. */
public final class VelocityBoot {
  private VelocityBoot() {}
  public static void install(ConduitRuntime runtime) {
    VelocityEnvironment environment = new VelocityEnvironment(runtime);
    ConduitPluginManager manager = runtime.pluginRuntime();
    manager.registerHandler(environment.loader());
    environment.attachNativeEvents();
  }
}
