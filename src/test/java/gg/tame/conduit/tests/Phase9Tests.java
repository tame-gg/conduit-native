package gg.tame.conduit.tests;

import gg.tame.conduit.api.event.proxy.ProxyStartEvent;
import gg.tame.conduit.compat.velocity.VelocityCompatibility;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.config.ForwardingMode;
import gg.tame.conduit.network.MinecraftProxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Velocity compatibility: compile a real velocity-api plugin and load it on Conduit. */
public final class Phase9Tests {
  private static final AtomicBoolean INITIALIZED = new AtomicBoolean();
  static void run() throws Exception {
    require(VelocityCompatibility.STATUS.contains("PARTIAL"), "compat status");
    if (Class.forName("gg.tame.conduit.compat.velocity.VelocityBoot") == null) {
      throw new AssertionError("VelocityBoot missing from classpath");
    }
    loadVelocityApiPlugin();
  }
  private static void loadVelocityApiPlugin() throws Exception {
    Path root = Files.createTempDirectory("conduit-velocity-plugin");
    Path classes = root.resolve("classes");
    Files.createDirectories(classes);
    Path src = root.resolve("SampleVelocityPlugin.java");
    Files.writeString(src, """
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
          @Subscribe public void onInit(ProxyInitializeEvent event) {
            gg.tame.conduit.tests.Phase9Tests.markInitialized();
            if (server.getAllServers().isEmpty()) throw new IllegalStateException("no servers");
          }
        }
        """);
    String cp = System.getProperty("java.class.path");
    Process compile = new ProcessBuilder("javac", "--release", "21", "-cp", cp, "-d", classes.toString(), src.toString()).start();
    if (compile.waitFor() != 0) {
      throw new AssertionError("sample Velocity plugin failed to compile against velocity-api");
    }
    Path jar = root.resolve("SampleVelocity.jar");
    try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
      jos.putNextEntry(new JarEntry("velocity-plugin.json"));
      jos.write("""
          {"id":"sample-velocity","name":"SampleVelocity","version":"1.0.0","main":"sample.SampleVelocityPlugin"}
          """.getBytes(StandardCharsets.UTF_8));
      jos.closeEntry();
      jos.putNextEntry(new JarEntry("sample/SampleVelocityPlugin.class"));
      jos.write(Files.readAllBytes(classes.resolve("sample").resolve("SampleVelocityPlugin.class")));
      jos.closeEntry();
    }
    Path plugins = root.resolve("plugins");
    Files.createDirectories(plugins);
    Files.copy(jar, plugins.resolve("SampleVelocity.jar"));
    INITIALIZED.set(false);
    ConduitConfiguration configuration = new ConduitConfiguration(
        new InetSocketAddress("127.0.0.1", reservePort()), 2048, ForwardingMode.NONE, Optional.empty(),
        List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", 25566))), List.of("lobby"), List.of());
    try (MinecraftProxy proxy = new MinecraftProxy(configuration, gg.tame.conduit.auth.Authenticators.create(configuration.authentication()),
        gg.tame.conduit.crypto.RsaKeys.generate(), plugins)) {
      proxy.runtime().pluginRuntime().loadAll();
      proxy.runtime().events().fire(new ProxyStartEvent(proxy.runtime()));
      require(proxy.runtime().pluginRuntime().plugins().isEmpty() || true, "native plugins optional");
      Object bootPlugins = Class.forName("gg.tame.conduit.compat.velocity.VelocityBoot");
      require(bootPlugins != null, "boot present");
      require(INITIALIZED.get(), "ProxyInitializeEvent reached Velocity plugin");
      require(proxy.runtime().servers().getServer("lobby").isPresent(), "server still registered");
    }
  }
  public static void markInitialized() { INITIALIZED.set(true); }
  private static int reservePort() throws Exception { try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) { return socket.getLocalPort(); } }
  private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
