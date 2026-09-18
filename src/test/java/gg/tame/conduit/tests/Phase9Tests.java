package gg.tame.conduit.tests;

import gg.tame.conduit.api.event.proxy.ProxyStartEvent;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.network.MinecraftProxy;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Velocity compatibility smoke test: a plugin compiled against the real velocity-api, loaded from
 * its jar, gets ProxyInitializeEvent and sees the configured servers. VelocityCompatTests covers
 * the rest.
 */
public final class Phase9Tests {
  public static void main(String[] a) throws Exception { run(); }

  static void run() throws Exception {
    VelocityCompatTests.installSignals();
    Path root = TempFiles.dir("conduit-velocity-plugin");
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    Path classes = VelocityCompatTests.compile(root, "sample.SampleVelocityPlugin", """
        package sample;
        import com.velocitypowered.api.event.Subscribe;
        import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
        import com.velocitypowered.api.plugin.Plugin;
        import com.velocitypowered.api.proxy.ProxyServer;
        import javax.inject.Inject;
        @Plugin(id = "sample-velocity", name = "SampleVelocity", version = "1.0.0", authors = {"conduit"})
        public final class SampleVelocityPlugin {
          private final ProxyServer server;
          @Inject public SampleVelocityPlugin(ProxyServer server) { this.server = server; }
          @SuppressWarnings("unchecked")
          @Subscribe public void onInit(ProxyInitializeEvent event) {
            ((java.util.Queue<String>) System.getProperties().get("velocity.test.signals")).add("initialized:" + server.getAllServers().size());
          }
        }
        """, List.of(), true);
    VelocityCompatTests.jar(classes, plugins.resolve("SampleVelocity.jar"), null);
    ConduitConfiguration configuration = VelocityCompatTests.configuration(
        List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", 25566))));
    try (MinecraftProxy proxy = new MinecraftProxy(configuration, gg.tame.conduit.auth.Authenticators.create(configuration.authentication()),
        gg.tame.conduit.crypto.RsaKeys.generate(), plugins)) {
      proxy.runtime().pluginRuntime().loadAll();
      require(proxy.runtime().plugins().plugin("sample-velocity").isPresent(), "Velocity plugin enabled as a Conduit plugin");
      proxy.runtime().events().fire(new ProxyStartEvent(proxy.runtime()));
      VelocityCompatTests.awaitSignal("initialized:1");
    }
    require(VelocityCompatTests.deletable(plugins.resolve("SampleVelocity.jar")), "plugin jar released at shutdown");
    System.out.println("Phase9Tests OK");
  }
  private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
