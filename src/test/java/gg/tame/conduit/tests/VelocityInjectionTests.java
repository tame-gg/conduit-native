// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.VelocityCompatTests.require;

import gg.tame.conduit.auth.Authenticators;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.crypto.RsaKeys;
import gg.tame.conduit.network.MinecraftProxy;
import gg.tame.conduit.runtime.ConduitRuntime;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Queue;

/**
 * A plugin that injects its own main class gets the one instance Conduit built, not a copy.
 *
 * <p>A copy is the same class but not the plugin: {@code EventManager.register} and
 * {@code PluginManager.fromInstance} do not know it, so a plugin whose helper hands its injected main
 * class to {@code register} failed to start, as SignedVelocity did.
 */
public final class VelocityInjectionTests {
  public static void main(String[] arguments) throws Exception { run(); }

  public static void run() throws Exception {
    theMainClassInjectsAsTheLivePlugin();
    System.out.println("VelocityInjectionTests OK");
  }

  private static final String PLUGIN = """
      package selfinject;
      import com.google.inject.AbstractModule;
      import com.google.inject.Injector;
      import com.velocitypowered.api.event.Subscribe;
      import com.velocitypowered.api.event.connection.PostLoginEvent;
      import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
      import com.velocitypowered.api.plugin.Plugin;
      import com.velocitypowered.api.proxy.ProxyServer;
      import javax.inject.Inject;
      @Plugin(id = "selfinject", name = "selfinject", version = "1.0")
      public final class Main {
        @SuppressWarnings("unchecked")
        static void signal(String value) { ((java.util.Queue<String>) System.getProperties().get("velocity.test.signals")).add(value); }

        public static final class Helper {
          @Inject Main plugin;
        }

        private final ProxyServer proxy;
        private final Injector injector;
        @Inject Helper fieldHelper;
        @Inject public Main(ProxyServer proxy, Injector injector) { this.proxy = proxy; this.injector = injector; }

        @Subscribe public void init(ProxyInitializeEvent event) {
          signal("field:" + (fieldHelper.plugin == this));
          signal("guice:" + (injector.getInstance(Main.class) == this));
          Helper built = injector.getInstance(Helper.class);
          signal("guice-helper:" + (built.plugin == this));
          signal("child:" + (injector.createChildInjector(new AbstractModule() {}).getInstance(Main.class) == this));
          signal("known:" + proxy.getPluginManager().fromInstance(built.plugin).isPresent());
          try {
            proxy.getEventManager().register(built.plugin, PostLoginEvent.class, e -> {});
            signal("register:ok");
          } catch (RuntimeException failure) {
            signal("register:" + failure);
          }
        }
      }
      """;

  private static void theMainClassInjectsAsTheLivePlugin() throws Exception {
    VelocityCompatTests.installSignals();
    Path root = TempFiles.dir("velocity-injection");
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    VelocityCompatTests.jar(VelocityCompatTests.compile(root, "selfinject.Main", PLUGIN, List.of(), true),
        plugins.resolve("selfinject.jar"), null);
    ConduitConfiguration configuration = VelocityCompatTests.configuration(
        List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", 1))));
    MinecraftProxy proxy = new MinecraftProxy(configuration, Authenticators.create(configuration.authentication()), RsaKeys.generate(), plugins);
    ConduitRuntime runtime = proxy.runtime();
    try {
      Queue<String> signals = signals();
      signals.clear();
      runtime.pluginRuntime().loadAll();
      runtime.started();
      waitFor(signals, "register:");
      require(signals.contains("field:true"), "a helper injected into the main class holds the live plugin, got " + signals);
      require(signals.contains("guice:true"), "the Injector gives the live plugin for its main class, got " + signals);
      require(signals.contains("guice-helper:true"), "a helper the Injector builds holds the live plugin, got " + signals);
      require(signals.contains("child:true"), "a child injector gives the live plugin too, got " + signals);
      require(signals.contains("known:true"), "PluginManager.fromInstance knows the injected instance, got " + signals);
      require(signals.contains("register:ok"), "EventManager.register accepts the injected instance, got " + signals);
    } finally {
      proxy.close();
    }
  }

  /** ProxyInitializeEvent runs on the adapter's threads, so the answers arrive a moment after started(). */
  private static void waitFor(Queue<String> signals, String prefix) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      for (String signal : signals) if (signal.startsWith(prefix)) return;
      Thread.sleep(20);
    }
  }

  @SuppressWarnings("unchecked")
  private static Queue<String> signals() { return (Queue<String>) System.getProperties().get(VelocityCompatTests.SIGNALS); }
}
