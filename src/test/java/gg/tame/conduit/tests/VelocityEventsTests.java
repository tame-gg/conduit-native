// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.VelocityCompatTests.awaitSignal;
import static gg.tame.conduit.tests.VelocityCompatTests.require;

import gg.tame.conduit.api.event.player.PlayerClientBrandEvent;
import gg.tame.conduit.api.event.player.PlayerServerConnectedEvent;
import gg.tame.conduit.api.event.player.PlayerSettingsChangedEvent;
import gg.tame.conduit.api.event.proxy.ProxyPreShutdownEvent;
import gg.tame.conduit.api.event.proxy.ProxyReloadEvent;
import gg.tame.conduit.api.event.proxy.ProxyShutdownEvent;
import gg.tame.conduit.api.event.proxy.ServerRegisteredEvent;
import gg.tame.conduit.api.event.proxy.ServerUnregisteredEvent;
import gg.tame.conduit.api.plugin.ConduitPlugin;
import gg.tame.conduit.api.plugin.PluginDescription;
import gg.tame.conduit.auth.Authenticators;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.crypto.RsaKeys;
import gg.tame.conduit.network.MinecraftProxy;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PluginMessage;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.tests.NativeApiTests.Backend;
import gg.tame.conduit.tests.NativeApiTests.Client;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Queue;
import java.util.logging.Logger;

/**
 * Velocity events Conduit used to register and never call, each raised from its native counterpart
 * by a real proxy: server registration, the client's settings and brand, reloads, the listener and
 * the shutdown. The plugin is compiled against the real velocity-api and loaded by the real loader.
 */
public final class VelocityEventsTests {
  public static void main(String[] arguments) throws Exception { run(); }

  private static final String VEVENTS = """
      package vevents;
      import com.velocitypowered.api.event.Subscribe;
      import com.velocitypowered.api.event.player.PlayerClientBrandEvent;
      import com.velocitypowered.api.event.player.PlayerSettingsChangedEvent;
      import com.velocitypowered.api.event.proxy.ListenerBoundEvent;
      import com.velocitypowered.api.event.proxy.ListenerCloseEvent;
      import com.velocitypowered.api.event.proxy.ProxyPreShutdownEvent;
      import com.velocitypowered.api.event.proxy.ProxyReloadEvent;
      import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
      import com.velocitypowered.api.event.proxy.server.ServerRegisteredEvent;
      import com.velocitypowered.api.event.proxy.server.ServerUnregisteredEvent;
      import com.velocitypowered.api.plugin.Plugin;
      import com.velocitypowered.api.proxy.ProxyServer;
      import javax.inject.Inject;

      @Plugin(id = "vevents", name = "VEvents", version = "1.0")
      public final class Main {
        @SuppressWarnings("unchecked")
        static void signal(String value) { ((java.util.Queue<String>) System.getProperties().get("velocity.test.signals")).add(value); }
        private final ProxyServer proxy;
        @Inject public Main(ProxyServer proxy) { this.proxy = proxy; }

        @Subscribe public void bound(ListenerBoundEvent event) { signal("bound:" + event.getAddress().getPort() + ":" + event.getListenerType().name()); }
        @Subscribe public void registered(ServerRegisteredEvent event) {
          signal("registered:" + event.registeredServer().getServerInfo().getName() + ":" + event.registeredServer().getServerInfo().getAddress().getPort()
              + ":" + proxy.getServer(event.registeredServer().getServerInfo().getName()).isPresent());
        }
        @Subscribe public void unregistered(ServerUnregisteredEvent event) {
          signal("unregistered:" + event.unregisteredServer().getServerInfo().getName()
              + ":" + proxy.getServer(event.unregisteredServer().getServerInfo().getName()).isPresent());
        }
        @Subscribe public void settings(PlayerSettingsChangedEvent event) {
          signal("settings:" + event.getPlayer().getUsername() + ":" + event.getPlayerSettings().getLocale().toLanguageTag());
        }
        @Subscribe public void brand(PlayerClientBrandEvent event) {
          signal("brand:" + event.getPlayer().getUsername() + ":" + event.getBrand() + ":" + event.getPlayer().getClientBrand());
        }
        @Subscribe public void reload(ProxyReloadEvent event) { signal("reload"); }
        @Subscribe public void close(ListenerCloseEvent event) { signal("close:" + event.getAddress().getPort()); }
        @Subscribe public void preShutdown(ProxyPreShutdownEvent event) throws InterruptedException {
          signal("pre-shutdown:" + proxy.getPlayerCount() + ":" + proxy.isShuttingDown());
          // A plugin asking again from inside the shutdown must not hold it up.
          proxy.shutdown();
          Thread.sleep(300);
          signal("pre-shutdown-done:" + proxy.getPlayerCount());
        }
        @Subscribe public void shutdown(ProxyShutdownEvent event) { signal("shutdown:" + proxy.getPlayerCount()); }
      }
      """;

  public static void run() throws Exception {
    VelocityCompatTests.installSignals();
    Path root = TempFiles.dir("velocity-events");
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    VelocityCompatTests.jar(VelocityCompatTests.compile(root, "vevents.Main", VEVENTS, List.of(), true), plugins.resolve("vevents.jar"), null);
    try (Backend lobby = new Backend("lobby"); Backend extra = new Backend("extra")) {
      ConduitConfiguration configuration = VelocityCompatTests.configuration(List.of(lobby.server()));
      MinecraftProxy proxy = new MinecraftProxy(configuration, Authenticators.create(configuration.authentication()), RsaKeys.generate(), plugins);
      NativeApiTests.Recorder recorder = new NativeApiTests.Recorder();
      ConduitPlugin observer = new ConduitPlugin() { };
      observer.attach(new PluginDescription("observer", "observer", "1", "Main", 1, List.of()), proxy.runtime(),
          Logger.getLogger("observer"), null, proxy.runtime().scheduler());
      proxy.runtime().events().register(observer, recorder);
      Thread serving = Thread.ofPlatform().daemon().name("velocity-events-serve").start(() -> {
        try { proxy.serve(); } catch (IOException ignored) { }
      });
      try {
        int port = proxy.port();
        awaitSignal("bound:" + port + ":MINECRAFT");

        // Registration, native and Velocity, after the fact: the server is there, then gone.
        var registered = proxy.runtime().servers().register("extra", extra.server().address());
        awaitSignal("registered:extra:" + extra.server().address().getPort() + ":true");
        require(recorder.of(ServerRegisteredEvent.class).getFirst().server() == registered, "the native event carries the new server");
        require(proxy.runtime().servers().unregister("extra"), "unregistered");
        awaitSignal("unregistered:extra:false");
        require(recorder.of(ServerUnregisteredEvent.class).getFirst().server() == registered, "and the server that went");
        require(!proxy.runtime().servers().unregister("extra") && recorder.of(ServerUnregisteredEvent.class).size() == 1,
            "a name that is not registered raises nothing");

        // A reload that applied is announced; one that failed is not.
        proxy.runtime().bindConfigPath(root.resolve("missing.toml"));
        require(!proxy.runtime().reload().applied() && recorder.of(ProxyReloadEvent.class).isEmpty(), "a failed reload raises nothing");
        Path config = root.resolve("conduit.toml");
        Files.writeString(config, """
            [listener]
            host="127.0.0.1"
            port=25565
            max-frame-bytes=65536
            [forwarding]
            mode="none"
            [servers.lobby]
            host="127.0.0.1"
            port=%d
            [routing]
            initial=["lobby"]
            fallback=["lobby"]
            [health]
            enabled=false
            """.formatted(lobby.server().address().getPort()));
        proxy.runtime().bindConfigPath(config);
        var reloaded = proxy.runtime().reload();
        require(reloaded.applied(), "reloaded, got " + reloaded.error());
        awaitSignal("reload");
        require(recorder.of(ProxyReloadEvent.class).size() == 1, "one native reload event");

        try (Client client = Client.join(port, "Settler")) {
          require(recorder.await(PlayerServerConnectedEvent.class, 1), "joined");
          var player = proxy.runtime().player("Settler").orElseThrow();
          require(player.clientBrand().isEmpty(), "no brand before the client sends one");
          client.send(clientSettings("de_de"));
          awaitSignal("settings:Settler:de-DE");
          require(recorder.of(PlayerSettingsChangedEvent.class).getFirst().player() == player, "the native event names the player");
          client.send(brand("vanilla"));
          awaitSignal("brand:Settler:vanilla:vanilla");
          require(recorder.of(PlayerClientBrandEvent.class).getFirst().brand().equals("vanilla")
              && player.clientBrand().orElseThrow().equals("vanilla"), "the native event and the player carry the brand");
          require(lobby.await(packet -> new String(packet, java.nio.charset.StandardCharsets.UTF_8).contains("vanilla")),
              "and the brand still reaches the backend");

          // The shutdown: the player is still there for the pre-shutdown handlers, and gone by ProxyShutdownEvent.
          proxy.close();
          awaitSignal("pre-shutdown:1:true");
          awaitSignal("pre-shutdown-done:1");
          awaitSignal("close:" + port);
          awaitSignal("shutdown:0");
          Queue<String> order = signals();
          List<String> seen = List.copyOf(order);
          require(seen.indexOf("pre-shutdown-done:1") < seen.indexOf("shutdown:0"), "the shutdown waited for the handler: " + seen);
          require(client.ends(), "the player was kicked once the handlers had run");
          var natives = recorder.names(ProxyPreShutdownEvent.class, ProxyShutdownEvent.class);
          require(natives.equals(List.of("ProxyPreShutdownEvent", "ProxyShutdownEvent")), "each once, in that order, got " + natives);
        }
        serving.join(15_000);
        require(!serving.isAlive(), "the proxy stopped, though a handler asked for a second shutdown");
      } finally {
        proxy.close();
        serving.join(10_000);
      }
    }
    System.out.println("VelocityEventsTests OK");
  }

  @SuppressWarnings("unchecked")
  private static Queue<String> signals() { return (Queue<String>) System.getProperties().get(VelocityCompatTests.SIGNALS); }

  private static final ProtocolDefinition P47 = ProtocolDefinition.forVersion(47);

  /** 1.8 Client Settings: language, view distance, chat mode, chat colours, skin parts. */
  private static byte[] clientSettings(String language) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, P47.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CLIENT_INFORMATION));
      MinecraftOutput.string(output, language);
      output.writeByte(8); output.writeByte(0); output.writeBoolean(true); output.writeByte(0x7F);
    }
    return bytes.toByteArray();
  }

  /** 1.8 MC|Brand: a string, as every version since writes it. */
  private static byte[] brand(String brand) throws IOException {
    ByteArrayOutputStream data = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(data)) { MinecraftOutput.string(output, brand); }
    return new PluginMessage("MC|Brand", data.toByteArray())
        .encode(P47.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_PLUGIN_MESSAGE));
  }
}
