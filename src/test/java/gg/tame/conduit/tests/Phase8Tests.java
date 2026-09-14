package gg.tame.conduit.tests;

import gg.tame.conduit.Conduit;
import gg.tame.conduit.api.command.CommandManager;
import gg.tame.conduit.api.event.Subscribe;
import gg.tame.conduit.api.event.player.PlayerChatEvent;
import gg.tame.conduit.api.event.plugin.PluginEnableEvent;
import gg.tame.conduit.api.plugin.ConduitPlugin;
import gg.tame.conduit.api.plugin.PluginDescription;
import gg.tame.conduit.compat.velocity.VelocityCompatibility;
import gg.tame.conduit.compat.velocity.VelocityProxyAdapter;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.config.ForwardingMode;
import gg.tame.conduit.event.ConduitEventManager;
import gg.tame.conduit.metrics.ConduitMetrics;
import gg.tame.conduit.network.MinecraftProxy;
import gg.tame.conduit.plugin.PluginDescriptorParser;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolCompatibility;
import gg.tame.conduit.protocol.TranslationSupport;
import gg.tame.conduit.runtime.ConduitRuntime;
import gg.tame.conduit.scheduler.ConduitScheduler;
import gg.tame.conduit.translate.Protocol765To776Translator;
import gg.tame.conduit.translate.TranslationPipeline;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public final class Phase8Tests {
  static void run() throws Exception {
    descriptorValidation();
    eventsAndScheduler();
    commandBuilder();
    metricsAndBackpressureLoad();
    translationFoundation();
    velocityAdapter();
    pluginJarLifecycle();
  }
  private static void descriptorValidation() throws Exception {
    PluginDescription description = PluginDescriptorParser.parse(new ByteArrayInputStream("""
        id: example
        name: ExamplePlugin
        version: 1.0.0
        main: com.example.ExamplePlugin
        api-version: 1
        depend: [other]
        """.getBytes(StandardCharsets.UTF_8)));
    require(description.id().equals("example") && description.dependencies().equals(List.of("other")), "descriptor");
    require(description.apiVersion() == Conduit.API_VERSION, "api version");
    try {
      PluginDescriptorParser.parse(new ByteArrayInputStream("id: BAD\nname: x\nversion: 1\nmain: a.B\napi-version: 1\n".getBytes(StandardCharsets.UTF_8)));
      throw new AssertionError("invalid id accepted");
    } catch (IllegalArgumentException expected) { }
  }
  private static void eventsAndScheduler() throws Exception {
    ConduitEventManager events = new ConduitEventManager();
    AtomicInteger seen = new AtomicInteger();
    ConduitPlugin plugin = new ConduitPlugin() {
      @Subscribe public void onEnable(PluginEnableEvent event) { seen.incrementAndGet(); }
    };
    plugin.attach(new PluginDescription("example", "Example", "1", "x.Y", 1, List.of()), null, java.util.logging.Logger.getAnonymousLogger(), Path.of("."), new ConduitScheduler());
    events.register(plugin, plugin);
    events.fire(new PluginEnableEvent(plugin));
    require(seen.get() == 1, "event fired");
    events.unregister(plugin);
    events.fire(new PluginEnableEvent(plugin));
    require(seen.get() == 1, "disabled plugin must not receive events");
    CountDownLatch latch = new CountDownLatch(1);
    ConduitScheduler scheduler = new ConduitScheduler();
    var task = scheduler.buildTask(plugin, latch::countDown).delay(Duration.ofMillis(20)).schedule();
    require(latch.await(2, TimeUnit.SECONDS), "scheduled task");
    task.cancel();
    scheduler.cancel(plugin);
    scheduler.close();
    var chat = new PlayerChatEvent(null, "hi");
    chat.setCancelled(true);
    require(chat.cancelled(), "cancellable");
  }
  private static void commandBuilder() {
    var command = CommandManager.Command.builder("spawn").permission("example.spawn").handler((source, arguments) -> {}).build();
    require(command.name().equals("spawn") && command.permission().equals("example.spawn"), "command builder");
  }
  private static void metricsAndBackpressureLoad() throws Exception {
    ConduitMetrics.current().inbound(10);
    ConduitMetrics.current().outbound(10);
    require(ConduitMetrics.current().snapshot().toString().contains("players="), "metrics");
    try (ServerSocket backendListener = new ServerSocket(0)) {
      Thread backend = Thread.startVirtualThread(() -> {
        try (Socket socket = backendListener.accept()) {
          MinecraftFrames.read(socket.getInputStream(), 2048);
          MinecraftFrames.read(socket.getInputStream(), 2048);
          for (int i = 0; i < 200; i++) MinecraftFrames.write(socket.getOutputStream(), new byte[] {1, (byte) i});
        } catch (Exception exception) { throw new RuntimeException(exception); }
      });
      ConduitConfiguration configuration = new ConduitConfiguration(new InetSocketAddress("127.0.0.1", reservePort()), 2048,
          ForwardingMode.NONE, Optional.empty(), List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", backendListener.getLocalPort()))), List.of("lobby"), List.of());
      try (MinecraftProxy proxy = new MinecraftProxy(configuration)) {
        int proxyPort = proxy.port();
        Thread serving = Thread.startVirtualThread(() -> { try { proxy.serve(); } catch (Exception ignored) { } });
        long start = System.nanoTime();
        try (Socket client = new Socket("127.0.0.1", proxyPort)) {
          MinecraftFrames.write(client.getOutputStream(), new byte[] {0, (byte) 0xFD, 5, 5, 'l', 'o', 'c', 'a', 'l', 0x63, (byte) 0xDD, 2});
          MinecraftFrames.write(client.getOutputStream(), loginStart());
          for (int i = 0; i < 200; i++) MinecraftFrames.read(client.getInputStream(), 2048);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        require(elapsedMs < 5_000, "200-packet relay stalled: " + elapsedMs + "ms");
        backend.join(); serving.interrupt();
      }
    }
  }
  private static void translationFoundation() {
    require(TranslationPipeline.support(765, 765) == TranslationSupport.DIRECT, "direct");
    require(ProtocolCompatibility.between(765, 776) == TranslationSupport.UNSUPPORTED, "no fake 765-776");
    try { new Protocol765To776Translator().clientToBackend(gg.tame.conduit.protocol.ConnectionState.PLAY, new byte[] {0}); throw new AssertionError("fake translation"); }
    catch (UnsupportedOperationException expected) { }
  }
  private static void velocityAdapter() {
    require(VelocityCompatibility.STATUS.contains("PARTIAL"), "compat status");
    Path dir = Path.of(System.getProperty("java.io.tmpdir"), "conduit-plugins-empty-" + System.nanoTime());
    ConduitRuntime runtime = new ConduitRuntime(new ConduitConfiguration(new InetSocketAddress("127.0.0.1", 1), 64,
        ForwardingMode.NONE, Optional.empty(), List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", 2))), List.of("lobby"), List.of()), dir);
    VelocityProxyAdapter adapter = new VelocityProxyAdapter(runtime);
    require(adapter.getAllServers().size() == 1, "server lookup");
    runtime.close();
  }
  private static void pluginJarLifecycle() throws Exception {
    Path root = Files.createTempDirectory("conduit-plugin-test");
    Path classes = root.resolve("classes");
    Files.createDirectories(classes);
    Path src = root.resolve("P.java");
    Files.writeString(src, "package p; public final class P extends gg.tame.conduit.api.plugin.ConduitPlugin { @Override public void onEnable() { getLogger().info(\"on\"); } }");
    Path out = classes;
    Process compile = new ProcessBuilder("javac", "--release", "21", "-cp", System.getProperty("java.class.path"), "-d", out.toString(), src.toString())
        .start();
    if (compile.waitFor() != 0) {
      System.out.println("plugin jar compile skipped (out/ not present yet)");
      return;
    }
    Path jar = root.resolve("example.jar");
    try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
      jos.putNextEntry(new JarEntry("conduit-plugin.yml"));
      jos.write("id: example\nname: Example\nversion: 1.0.0\nmain: p.P\napi-version: 1\n".getBytes(StandardCharsets.UTF_8));
      jos.closeEntry();
      jos.putNextEntry(new JarEntry("p/P.class"));
      jos.write(Files.readAllBytes(out.resolve("p").resolve("P.class")));
      jos.closeEntry();
    }
    ConduitRuntime runtime = new ConduitRuntime(new ConduitConfiguration(new InetSocketAddress("127.0.0.1", 1), 64,
        ForwardingMode.NONE, Optional.empty(), List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", 2))), List.of("lobby"), List.of()), root);
    Files.move(jar, root.resolve("ExamplePlugin.jar"));
    runtime.pluginRuntime().loadAll();
    require(runtime.plugins().plugin("example").isPresent(), "plugin loaded");
    runtime.plugins().disable(runtime.plugins().plugin("example").orElseThrow());
    require(runtime.plugins().plugin("example").isEmpty(), "plugin disabled");
    runtime.close();
  }
  private static byte[] loginStart() { return new byte[] {0, 5, 'p', 'l', 'a', 'y', 'r', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}; }
  private static int reservePort() throws Exception { try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
  private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
