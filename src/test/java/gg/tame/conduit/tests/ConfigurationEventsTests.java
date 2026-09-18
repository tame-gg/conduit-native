// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.NativeApiTests.require;
import static gg.tame.conduit.tests.ResourcePackTests.bytes;
import static gg.tame.conduit.tests.ResourcePackTests.readUntil;
import static gg.tame.conduit.tests.VelocityCompatTests.awaitSignal;

import gg.tame.conduit.api.event.player.PlayerConfigurationEvent;
import gg.tame.conduit.api.event.player.PlayerServerConnectedEvent;
import gg.tame.conduit.api.plugin.ConduitPlugin;
import gg.tame.conduit.api.plugin.PluginDescription;
import gg.tame.conduit.auth.Authenticators;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.crypto.RsaKeys;
import gg.tame.conduit.network.MinecraftProxy;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.tests.NativeApiTests.Backend;
import gg.tame.conduit.tests.NativeApiTests.Client;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * The Configuration phase as plugins see it: a scripted 1.20.4 client joins and switches between
 * two scripted 1.20.4 servers, with one compiled Velocity plugin recording the five configuration
 * events and another sending a pack from PlayerConfigurationEvent and holding the phase until the
 * client says it loaded. A 1.8 client, which has no Configuration phase, raises none of them.
 */
public final class ConfigurationEventsTests {
  public static void main(String[] arguments) throws Exception { run(); }

  private static final String ORDER = """
      package vcfgorder;
      import com.velocitypowered.api.event.Subscribe;
      import com.velocitypowered.api.event.player.configuration.PlayerConfigurationEvent;
      import com.velocitypowered.api.event.player.configuration.PlayerEnterConfigurationEvent;
      import com.velocitypowered.api.event.player.configuration.PlayerEnteredConfigurationEvent;
      import com.velocitypowered.api.event.player.configuration.PlayerFinishConfigurationEvent;
      import com.velocitypowered.api.event.player.configuration.PlayerFinishedConfigurationEvent;
      import com.velocitypowered.api.plugin.Plugin;
      import com.velocitypowered.api.proxy.Player;
      import com.velocitypowered.api.proxy.ServerConnection;

      @Plugin(id = "vcfgorder", name = "VCfgOrder", version = "1.0")
      public final class Main {
        @SuppressWarnings("unchecked")
        static void signal(String value) { ((java.util.Queue<String>) System.getProperties().get("velocity.test.signals")).add(value); }
        static String at(Player player, ServerConnection server) {
          return player.getUsername() + ":" + server.getServerInfo().getName() + ":" + player.getProtocolState();
        }
        @Subscribe public void enter(PlayerEnterConfigurationEvent event) { signal("enter:" + at(event.player(), event.server())); }
        @Subscribe public void entered(PlayerEnteredConfigurationEvent event) { signal("entered:" + at(event.player(), event.server())); }
        @Subscribe public void configuration(PlayerConfigurationEvent event) { signal("configuration:" + at(event.player(), event.server())); }
        @Subscribe public void finish(PlayerFinishConfigurationEvent event) { signal("finish:" + at(event.player(), event.server())); }
        @Subscribe public void finished(PlayerFinishedConfigurationEvent event) { signal("finished:" + at(event.player(), event.server())); }
        // ForcePack's configuration mode relies on the first server's connection coming after its phase.
        @Subscribe public void connected(com.velocitypowered.api.event.player.ServerPostConnectEvent event) {
          signal("postconnect:" + event.getPlayer().getUsername() + ":" + event.getPlayer().getCurrentServer().map(on -> on.getServerInfo().getName()).orElse("none"));
        }
      }
      """;

  /** Sends each server's pack from PlayerConfigurationEvent and holds the phase until the client has loaded it. */
  private static final String PACK = """
      package vcfgpack;
      import com.velocitypowered.api.event.EventTask;
      import com.velocitypowered.api.event.Subscribe;
      import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent;
      import com.velocitypowered.api.event.player.configuration.PlayerConfigurationEvent;
      import com.velocitypowered.api.plugin.Plugin;
      import com.velocitypowered.api.proxy.ProxyServer;
      import java.util.UUID;
      import java.util.concurrent.CompletableFuture;
      import java.util.concurrent.ConcurrentHashMap;
      import javax.inject.Inject;

      @Plugin(id = "vcfgpack", name = "VCfgPack", version = "1.0")
      public final class Main {
        @SuppressWarnings("unchecked")
        static void signal(String value) { ((java.util.Queue<String>) System.getProperties().get("velocity.test.signals")).add(value); }
        private final ProxyServer proxy;
        private final ConcurrentHashMap<UUID, CompletableFuture<Void>> loading = new ConcurrentHashMap<>();
        @Inject public Main(ProxyServer proxy) { this.proxy = proxy; }

        @Subscribe public EventTask configure(PlayerConfigurationEvent event) {
          String server = event.server().getServerInfo().getName();
          UUID id = UUID.nameUUIDFromBytes(server.getBytes(java.nio.charset.StandardCharsets.UTF_8));
          CompletableFuture<Void> loaded = new CompletableFuture<>();
          loading.put(id, loaded);
          event.player().sendResourcePackOffer(proxy.createResourcePackBuilder("https://example.invalid/" + server + ".zip")
              .setId(id).setHash(new byte[20]).setShouldForce(true).build());
          signal("sent:" + server);
          return EventTask.resumeWhenComplete(loaded);
        }
        @Subscribe public void status(PlayerResourcePackStatusEvent event) {
          signal("status:" + event.getPackId() + ":" + event.getStatus() + ":" + event.getPlayer().getProtocolState());
          if (event.getStatus() == PlayerResourcePackStatusEvent.Status.SUCCESSFUL) {
            CompletableFuture<Void> loaded = loading.remove(event.getPackId());
            if (loaded != null) loaded.complete(null);
          }
        }
      }
      """;

  private static final ProtocolDefinition P765 = ProtocolDefinition.forVersion(765);

  public static void run() throws Exception {
    Path root = TempFiles.dir("configuration-events");
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    VelocityCompatTests.jar(VelocityCompatTests.compile(root, "vcfgorder.Main", ORDER, List.of(), true), plugins.resolve("vcfgorder.jar"), null);
    VelocityCompatTests.jar(VelocityCompatTests.compile(root, "vcfgpack.Main", PACK, List.of(), true), plugins.resolve("vcfgpack.jar"), null);
    aModernClientIsConfiguredByPlugins(plugins);
    anOlderClientHasNoConfigurationPhase(plugins);
    System.out.println("ConfigurationEventsTests OK");
  }

  private static void aModernClientIsConfiguredByPlugins(Path plugins) throws Exception {
    VelocityCompatTests.installSignals();
    int configOut = P765.id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_PLUGIN_MESSAGE);
    byte finishOut = (byte) P765.id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_FINISH);
    byte finishIn = (byte) P765.id(ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_FINISH);
    byte joinGame = (byte) P765.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN);
    byte start = (byte) P765.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_START_CONFIGURATION);
    byte configurationAck = (byte) P765.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CONFIGURATION_ACKNOWLEDGED);
    int status = P765.id(ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_RESOURCE_PACK_STATUS);
    try (ServerSocket lobbyListener = new ServerSocket(0); ServerSocket otherListener = new ServerSocket(0)) {
      ModLoaderTests.Mock lobby = new ModLoaderTests.Mock(lobbyListener, "lobby", new byte[0], configOut, finishOut, finishIn);
      ModLoaderTests.Mock other = new ModLoaderTests.Mock(otherListener, "other", new byte[0], configOut, finishOut, finishIn);
      Thread.ofPlatform().daemon().start(lobby);
      Thread.ofPlatform().daemon().start(other);
      ConduitConfiguration configuration = new ConduitConfiguration(new InetSocketAddress("127.0.0.1", ModLoaderTests.reservePort()), 8192,
          gg.tame.conduit.config.ForwardingMode.NONE, Optional.empty(),
          List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", lobbyListener.getLocalPort())),
              new BackendServer("other", new InetSocketAddress("127.0.0.1", otherListener.getLocalPort()))),
          List.of("lobby"), List.of());
      try (MinecraftProxy proxy = new MinecraftProxy(configuration, Authenticators.create(configuration.authentication()), RsaKeys.generate(), plugins)) {
        NativeApiTests.Recorder recorder = record(proxy);
        Thread.ofPlatform().daemon().start(() -> { try { proxy.serve(); } catch (Exception ignored) { } });
        try (Socket client = new Socket("127.0.0.1", proxy.port())) {
          client.setSoTimeout(15_000);
          InputStream in = client.getInputStream();
          OutputStream out = client.getOutputStream();
          MinecraftFrames.write(out, new Handshake(765, "localhost", 25565, 2).encode());
          MinecraftFrames.write(out, ModLoaderTests.loginStart());
          require(MinecraftFrames.read(in, 8192)[0] == 0x02, "Login Success");
          MinecraftFrames.write(out, new byte[] {0x03});
          configuredWithPack(in, out, "lobby", finishOut, status);
          MinecraftFrames.write(out, new byte[] {finishIn});
          readUntil(in, packet -> packet[0] == joinGame);
          awaitSignal("finished:playr:lobby:PLAY");
          awaitSignal("postconnect:playr:lobby");

          MinecraftFrames.write(out, ModLoaderTests.command(
              P765.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT_COMMAND), "server other"));
          readUntil(in, packet -> packet[0] == start);
          MinecraftFrames.write(out, new byte[] {configurationAck});
          configuredWithPack(in, out, "other", finishOut, status);
          MinecraftFrames.write(out, new byte[] {finishIn});
          readUntil(in, packet -> packet[0] == joinGame);
          awaitSignal("finished:playr:other:PLAY");
        }
        List<String> order = signals().stream().filter(signal -> signal.matches("(enter|entered|configuration|finish|finished):.*")).toList();
        require(order.equals(List.of(
            "entered:playr:lobby:CONFIGURATION", "configuration:playr:lobby:CONFIGURATION", "finish:playr:lobby:CONFIGURATION", "finished:playr:lobby:PLAY",
            "enter:playr:other:PLAY", "entered:playr:other:CONFIGURATION", "configuration:playr:other:CONFIGURATION",
            "finish:playr:other:CONFIGURATION", "finished:playr:other:PLAY")),
            "Velocity's events in Velocity's order, Enter on the switch only: " + order);
        List<String> stages = recorder.of(PlayerConfigurationEvent.class).stream().map(event -> event.stage() + ":" + event.server().getName()).toList();
        require(stages.equals(List.of("ENTERED:lobby", "FINISHING:lobby", "FINISHED:lobby",
            "ENTERING:other", "ENTERED:other", "FINISHING:other", "FINISHED:other")), "the native stages: " + stages);
        List<String> natives = recorder.names(PlayerConfigurationEvent.class, PlayerServerConnectedEvent.class);
        require(natives.subList(0, 4).equals(List.of("PlayerConfigurationEvent", "PlayerConfigurationEvent", "PlayerConfigurationEvent",
            "PlayerServerConnectedEvent")), "the player is on their first server once its phase has finished: " + natives);
        List<String> seen = List.copyOf(signals());
        require(seen.indexOf("postconnect:playr:lobby") > seen.indexOf("finish:playr:lobby:CONFIGURATION")
            && seen.indexOf("finish:playr:lobby:CONFIGURATION") >= 0, "and so is ServerPostConnectEvent: " + seen);
        require(lobby.received.stream().noneMatch(packet -> packet[0] == (byte) status)
            && other.received.stream().noneMatch(packet -> packet[0] == (byte) status), "the answers about the proxy's packs stayed at the proxy");
      }
    }
  }

  /**
   * Reads the server's Configuration phase as far as the plugin's pack, answers it, and reads on to
   * Finish Configuration, which must wait for the plugin to have heard the pack loaded.
   */
  private static void configuredWithPack(InputStream in, OutputStream out, String server, byte finishOut, int status) throws Exception {
    byte push = (byte) P765.id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_RESOURCE_PACK_PUSH);
    List<byte[]> first = readUntil(in, packet -> packet[0] == push || (packet.length == 1 && packet[0] == finishOut));
    require(first.getLast()[0] == push, server + ": the plugin's pack is pushed in Configuration, before Finish Configuration");
    ByteBuffer body = ByteBuffer.wrap(first.getLast(), 1, 16);
    UUID id = new UUID(body.getLong(), body.getLong());
    require(id.equals(UUID.nameUUIDFromBytes(server.getBytes(StandardCharsets.UTF_8))), server + ": the plugin's own pack id, got " + id);
    require(new String(first.getLast(), StandardCharsets.UTF_8).contains("https://example.invalid/" + server + ".zip"), server + ": its URL");
    for (int result : new int[] {3, 0}) {
      MinecraftFrames.write(out, bytes(status, output -> {
        output.writeLong(id.getMostSignificantBits()); output.writeLong(id.getLeastSignificantBits()); MinecraftOutput.varInt(output, result);
      }));
    }
    readUntil(in, packet -> packet.length == 1 && packet[0] == finishOut);
    require(signals().contains("status:" + id + ":ACCEPTED:CONFIGURATION") && signals().contains("status:" + id + ":SUCCESSFUL:CONFIGURATION"),
        server + ": the plugin heard both answers before the client was told to finish: " + signals());
  }

  /** A 1.8 client joins and switches: no Configuration phase, so no stage and no Velocity event. */
  private static void anOlderClientHasNoConfigurationPhase(Path plugins) throws Exception {
    VelocityCompatTests.installSignals();
    try (Backend lobby = new Backend("lobby"); Backend other = new Backend("other")) {
      ConduitConfiguration configuration = VelocityCompatTests.configuration(List.of(lobby.server(), other.server()));
      try (MinecraftProxy proxy = new MinecraftProxy(configuration, Authenticators.create(configuration.authentication()), RsaKeys.generate(), plugins)) {
        NativeApiTests.Recorder recorder = record(proxy);
        Thread.ofPlatform().daemon().start(() -> { try { proxy.serve(); } catch (Exception ignored) { } });
        try (Client client = Client.join(proxy.port(), "Oldie")) {
          require(recorder.await(PlayerServerConnectedEvent.class, 1), "joined");
          var moved = proxy.runtime().player("Oldie").orElseThrow()
              .connectWithResult(proxy.runtime().servers().getServer("other").orElseThrow()).get(10, TimeUnit.SECONDS);
          require(moved.status() == gg.tame.conduit.api.player.ConnectResult.Status.CONNECTED, "switched, got " + moved);
        }
        require(recorder.of(PlayerConfigurationEvent.class).isEmpty(), "no stage for a 1.8 client");
        require(signals().stream().noneMatch(signal -> signal.matches("(enter|entered|configuration|finish|finished|sent):.*")),
            "and no Velocity configuration event: " + signals());
      }
    }
  }

  private static NativeApiTests.Recorder record(MinecraftProxy proxy) {
    NativeApiTests.Recorder recorder = new NativeApiTests.Recorder();
    ConduitPlugin observer = new ConduitPlugin() { };
    observer.attach(new PluginDescription("observer", "observer", "1", "Main", 1, List.of()), proxy.runtime(),
        Logger.getLogger("observer"), null, proxy.runtime().scheduler());
    proxy.runtime().events().register(observer, recorder);
    return recorder;
  }

  @SuppressWarnings("unchecked")
  private static Queue<String> signals() { return (Queue<String>) System.getProperties().get(VelocityCompatTests.SIGNALS); }
}
