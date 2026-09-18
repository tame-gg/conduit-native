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
    Path plugins = configPath.toAbsolutePath().getParent() == null ? Path.of("plugins") : configPath.toAbsolutePath().getParent().resolve("plugins");
    gg.tame.conduit.config.ConfigMigrator.migrate(configPath);
    config = ConfigurationLoader.load(configPath);
    try (MinecraftProxy listener = new MinecraftProxy(config, gg.tame.conduit.auth.Authenticators.create(config.authentication()), gg.tame.conduit.crypto.RsaKeys.generate(), plugins)) {
      listener.runtime().bindConfigPath(configPath);
      System.out.println("Conduit foundation listening on " + config.listener().getHostString() + ":" + listener.port());
      listener.probeBackends();
      consoleCommands(listener.runtime());
      listener.serve();
    }
  }

  /** Commands typed at the proxy's own terminal. Daemon: it must not hold shutdown open. */
  private static void consoleCommands(gg.tame.conduit.runtime.ConduitRuntime runtime) {
    Thread reader = new Thread(() -> {
      var console = new gg.tame.conduit.command.ConsoleCommandSource();
      try (var input = new java.io.BufferedReader(new java.io.InputStreamReader(System.in))) {
        for (String line = input.readLine(); line != null; line = input.readLine()) {
          if (line.isBlank()) continue;
          try {
            if (!runtime.commandManager().dispatch(console, line)) console.sendMessage("Unknown command. Try /conduit help");
          } catch (RuntimeException failure) { gg.tame.conduit.log.ConduitLog.error("console command failed", failure); }
        }
      } catch (java.io.IOException closed) { }
    }, "conduit-console");
    reader.setDaemon(true);
    reader.start();
  }
}
