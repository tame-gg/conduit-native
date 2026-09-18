// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.VelocityCompatTests.require;

import gg.tame.conduit.api.event.messaging.PluginMessageEvent;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.auth.Authenticators;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.crypto.RsaKeys;
import gg.tame.conduit.network.MinecraftProxy;
import gg.tame.conduit.runtime.ConduitRuntime;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Queue;
import java.util.UUID;

/**
 * What a Velocity plugin leaves behind when Conduit disables it, as seen by the plugins that stay.
 * Plugins are compiled against the real velocity-api and loaded by the real loader; the proxy is not
 * served, and its native events are raised directly.
 */
public final class VelocityLifecycleTests {
  public static void main(String[] arguments) throws Exception { run(); }

  public static void run() throws Exception {
    aDisabledPluginsChannelsGoWithIt();
    System.out.println("VelocityLifecycleTests OK");
  }

  private static final String SIGNAL = """
        @SuppressWarnings("unchecked")
        static void signal(String value) { ((java.util.Queue<String>) System.getProperties().get("velocity.test.signals")).add(value); }
      """;

  /** Registers the channels its id names, and nothing else. */
  private static String registrant(String id, String... channels) {
    StringBuilder register = new StringBuilder();
    for (String channel : channels) {
      register.append("proxy.getChannelRegistrar().register(MinecraftChannelIdentifier.from(\"").append(channel).append("\"));\n");
    }
    return """
        package %1$s;
        import com.velocitypowered.api.event.Subscribe;
        import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
        import com.velocitypowered.api.plugin.Plugin;
        import com.velocitypowered.api.proxy.ProxyServer;
        import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
        import javax.inject.Inject;
        @Plugin(id = "%1$s", name = "%1$s", version = "1.0")
        public final class Main {
          private final ProxyServer proxy;
          @Inject public Main(ProxyServer proxy) { this.proxy = proxy; }
          @Subscribe public void init(ProxyInitializeEvent event) {
            %2$s
          }
        }
        """.formatted(id, register);
  }

  private static final String WATCHER = """
      package watcher;
      import com.velocitypowered.api.event.Subscribe;
      import com.velocitypowered.api.event.connection.PluginMessageEvent;
      import com.velocitypowered.api.plugin.Plugin;
      @Plugin(id = "watcher", name = "watcher", version = "1.0")
      public final class Main {
      """ + SIGNAL + """
        @Subscribe public void heard(PluginMessageEvent event) { signal("heard:" + event.getIdentifier().getId()); }
      }
      """;

  /**
   * Velocity's registrar is not told who registers a channel, and a disabled plugin's channels used
   * to stay registered for the life of the proxy: every other plugin listening for plugin messages
   * went on hearing traffic on channels nobody served any more. A channel now goes with the last
   * plugin that registered it, and stays while another that registered it too is still enabled.
   */
  private static void aDisabledPluginsChannelsGoWithIt() throws Exception {
    VelocityCompatTests.installSignals();
    Path root = TempFiles.dir("velocity-lifecycle-channels");
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    VelocityCompatTests.jar(VelocityCompatTests.compile(root, "alone.Main", registrant("alone", "test:alone", "test:shared"), List.of(), true),
        plugins.resolve("alone.jar"), null);
    VelocityCompatTests.jar(VelocityCompatTests.compile(root, "sharer.Main", registrant("sharer", "test:shared"), List.of(), true),
        plugins.resolve("sharer.jar"), null);
    VelocityCompatTests.jar(VelocityCompatTests.compile(root, "watcher.Main", WATCHER, List.of(), true),
        plugins.resolve("watcher.jar"), null);
    ConduitConfiguration configuration = VelocityCompatTests.configuration(
        List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", 1))));
    MinecraftProxy proxy = new MinecraftProxy(configuration, Authenticators.create(configuration.authentication()), RsaKeys.generate(), plugins);
    ConduitRuntime runtime = proxy.runtime();
    try {
      runtime.pluginRuntime().loadAll();
      runtime.started();
      Player player = onLobby(runtime);
      Queue<String> heard = signals();
      message(runtime, player, "test:alone");
      message(runtime, player, "test:shared");
      require(heard.contains("heard:test:alone") && heard.contains("heard:test:shared"), "registered channels are heard, got " + heard);

      runtime.plugins().disable(runtime.plugins().plugin("alone").orElseThrow());
      heard.clear();
      message(runtime, player, "test:alone");
      message(runtime, player, "test:shared");
      require(!heard.contains("heard:test:alone"), "the disabled plugin's own channel went with it, got " + heard);
      require(heard.contains("heard:test:shared"), "a channel another enabled plugin registered too stays, got " + heard);

      runtime.plugins().disable(runtime.plugins().plugin("sharer").orElseThrow());
      heard.clear();
      message(runtime, player, "test:shared");
      require(!heard.contains("heard:test:shared"), "and goes with the last plugin that registered it, got " + heard);
    } finally {
      proxy.close();
    }
  }

  @SuppressWarnings("unchecked")
  private static Queue<String> signals() { return (Queue<String>) System.getProperties().get(VelocityCompatTests.SIGNALS); }

  private static void message(ConduitRuntime runtime, Player player, String channel) {
    runtime.events().fire(new PluginMessageEvent(player, channel, new byte[] { 1 }, PluginMessageEvent.Direction.CLIENT_TO_PROXY));
  }

  /**
   * A player on the lobby, answering only what the Velocity layer asks of a player it raises a
   * plugin message for. A dynamic proxy, so the Player interface can grow without breaking it.
   */
  private static Player onLobby(ConduitRuntime runtime) {
    var lobby = runtime.servers().getServer("lobby").orElseThrow();
    UUID id = UUID.nameUUIDFromBytes("lifecycle".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    Player.OptionalServer current = new Player.OptionalServer() {
      @Override public boolean isPresent() { return true; }
      @Override public gg.tame.conduit.api.server.RegisteredServer orElse(gg.tame.conduit.api.server.RegisteredServer fallback) { return lobby; }
      @Override public String name() { return lobby.getName(); }
    };
    return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] { Player.class }, (self, method, args) -> switch (method.getName()) {
      case "uniqueId" -> id;
      case "username" -> "Lifecycle";
      case "currentServer" -> current;
      case "protocolVersion" -> 47;
      case "connectionState" -> "PLAY";
      case "hashCode" -> System.identityHashCode(self);
      case "equals" -> self == args[0];
      case "toString" -> "Lifecycle";
      default -> method.isDefault() ? java.lang.reflect.InvocationHandler.invokeDefault(self, method, args)
          : method.getReturnType() == boolean.class ? false : null;
    });
  }
}
