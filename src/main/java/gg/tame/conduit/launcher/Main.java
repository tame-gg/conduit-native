package gg.tame.conduit.launcher;

import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.config.ConfigurationLoader;
import gg.tame.conduit.forwarding.ForwardingSecret;
import gg.tame.conduit.forwarding.Forwarders;
import gg.tame.conduit.network.MinecraftProxy;
import java.nio.file.Path;

public final class Main {
  private Main() {}
  public static void main(String[] arguments) throws Exception {
    boolean checkOnly = arguments.length > 0 && arguments[0].equals("--check-config");
    Path configPath = Path.of(arguments[checkOnly ? 1 : 0]);
    ConduitConfiguration config = ConfigurationLoader.load(configPath);
    if (config.forwardingSecretFile().isPresent()) System.out.println("Modern forwarding secret loaded (fingerprint " + ForwardingSecret.load(config.forwardingSecretFile().get()).fingerprint() + ").");
    System.out.println("Authentication mode: " + config.authentication().mode().name().toLowerCase());
    Forwarders.create(config);
    if (checkOnly) { System.out.println("Configuration valid."); return; }
    try (MinecraftProxy listener = new MinecraftProxy(config)) {
      System.out.println("Conduit foundation listening on " + config.listener().getHostString() + ":" + listener.port());
      listener.serve();
    }
  }
}
