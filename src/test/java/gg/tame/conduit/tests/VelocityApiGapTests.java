// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.NativeApiTests.waitFor;

import gg.tame.conduit.auth.Authenticators;
import gg.tame.conduit.config.AuthenticationSettings;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.config.ForwardingMode;
import gg.tame.conduit.config.MetricsSettings;
import gg.tame.conduit.config.OpsSettings;
import gg.tame.conduit.crypto.RsaKeys;
import gg.tame.conduit.network.MinecraftProxy;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.routing.ForcedHosts;
import gg.tame.conduit.tests.LoginFlowTests.Proxy;
import gg.tame.conduit.tests.NativeApiTests.Backend;
import gg.tame.conduit.tests.NativeApiTests.Client;
import gg.tame.conduit.tests.PlayerExtrasTests.ModernBackend;
import gg.tame.conduit.tests.PlayerExtrasTests.ModernClient;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Velocity API that used to be missing: what Guice injects beyond Conduit's own list, forced hosts
 * and the GameSpy 4 query in {@code getConfiguration()}, ProxyQueryEvent, CookieStoreEvent and
 * CookieRequestEvent around a plugin's own cookies, and a legacy Forge client's mod list as
 * {@code getModInfo} and PlayerModInfoEvent. One compiled plugin, a real proxy, scripted clients.
 */
public final class VelocityApiGapTests {
  public static void main(String[] arguments) throws Exception { run(); }

  public static void run() throws Exception {
    Queue<String> signals = new ConcurrentLinkedQueue<>();
    System.getProperties().put("gaps.test.signals", signals);
    try {
      Path plugins = LoginFlowTests.compiledPlugin("gaps.Main", PLUGIN);
      guiceConfigurationAndQuery(plugins, signals);
      cookieEventsDecide(plugins, signals);
      aLegacyForgeModListIsRead(plugins, signals);
    } finally {
      System.getProperties().remove("gaps.test.signals");
    }
    System.out.println("VelocityApiGapTests OK");
  }

  private static final String PLUGIN = """
      package gaps;
      import com.velocitypowered.api.command.SimpleCommand;
      import com.velocitypowered.api.event.Subscribe;
      import com.velocitypowered.api.event.player.CookieReceiveEvent;
      import com.velocitypowered.api.event.player.CookieRequestEvent;
      import com.velocitypowered.api.event.player.CookieStoreEvent;
      import com.velocitypowered.api.event.player.PlayerModInfoEvent;
      import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
      import com.velocitypowered.api.event.query.ProxyQueryEvent;
      import com.velocitypowered.api.plugin.Plugin;
      import com.velocitypowered.api.plugin.PluginContainer;
      import com.velocitypowered.api.proxy.Player;
      import com.velocitypowered.api.proxy.ProxyServer;
      import com.velocitypowered.api.proxy.messages.ChannelRegistrar;
      import com.velocitypowered.api.proxy.server.QueryResponse;
      import java.util.stream.Collectors;
      import javax.inject.Inject;
      import net.kyori.adventure.key.Key;

      @Plugin(id = "gaps", name = "gaps", version = "1.0")
      public final class Main {
        @SuppressWarnings("unchecked")
        static void signal(String value) { ((java.util.Queue<String>) System.getProperties().get("gaps.test.signals")).add(value); }
        private final ProxyServer proxy;

        @Inject public Main(ProxyServer proxy, com.google.inject.Provider<ProxyServer> provider, java.util.concurrent.ExecutorService executor,
                            ChannelRegistrar channels, StringBuilder built, PluginContainer container) {
          this.proxy = proxy;
          signal("provider:" + (provider.get() == proxy));
          signal("executor:" + (executor == container.getExecutorService()));
          signal("channels:" + (channels == proxy.getChannelRegistrar()));
          signal("built:" + (built != null));
        }

        @Subscribe public void init(ProxyInitializeEvent event) {
          var config = proxy.getConfiguration();
          signal("forced:" + config.getForcedHosts());
          signal("query:" + config.isQueryEnabled() + ":" + (config.getQueryPort() > 0));
          proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("gaps").plugin(this).build(), (SimpleCommand) invocation -> {
            Player player = proxy.getPlayer(invocation.arguments()[0]).orElseThrow();
            player.storeCookie(Key.key("gaps", "k"), new byte[] {1});
            player.storeCookie(Key.key("gaps", "other"), new byte[] {2});
            player.storeCookie(Key.key("gaps", "deny"), new byte[] {3});
            player.requestCookie(Key.key("gaps", "deny"));
            player.requestCookie(Key.key("gaps", "k"));
            signal("cookies-sent");
          });
        }
        @Subscribe public void query(ProxyQueryEvent event) {
          signal("queried:" + event.getQueryType());
          event.setResponse(event.getResponse().toBuilder().map("gapmap").plugins(QueryResponse.PluginInformation.of("gaps", "1.0")).build());
        }
        // A stored cookie's data becomes its first byte plus six; "deny" is neither stored nor asked for.
        @Subscribe public void store(CookieStoreEvent event) {
          if (event.getOriginalKey().value().equals("deny")) event.setResult(CookieStoreEvent.ForwardResult.handled());
          else event.setResult(CookieStoreEvent.ForwardResult.data(new byte[] {(byte) (event.getOriginalData()[0] + 6)}));
        }
        @Subscribe public void request(CookieRequestEvent event) {
          if (event.getOriginalKey().value().equals("deny")) event.setResult(CookieRequestEvent.ForwardResult.handled());
          else event.setResult(CookieRequestEvent.ForwardResult.key(Key.key("gaps", "other")));
        }
        @Subscribe public void cookie(CookieReceiveEvent event) {
          signal("cookie:" + event.getOriginalKey().asString() + ":" + java.util.Arrays.toString(event.getOriginalData()));
        }
        @Subscribe public void mods(PlayerModInfoEvent event) {
          signal("mods:" + event.getPlayer().getUsername() + ":" + event.getModInfo().getType() + ":"
              + event.getModInfo().getMods().stream().map(mod -> mod.getId() + "@" + mod.getVersion()).collect(Collectors.joining(","))
              + ":" + event.getPlayer().getModInfo().isPresent());
        }
      }
      """;

  /** Guice fills in what Conduit does not; forced hosts and the query reach getConfiguration; ProxyQueryEvent changes the answer. */
  private static void guiceConfigurationAndQuery(Path plugins, Queue<String> signals) throws Exception {
    int queryPort;
    try (DatagramSocket probe = new DatagramSocket(0)) { queryPort = probe.getLocalPort(); }
    ConduitConfiguration base = VelocityCompatTests.configuration(List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", 1))));
    OpsSettings o = base.ops();
    OpsSettings ops = new OpsSettings(o.schemaVersion(), o.maintenance(), o.health(), o.versions(), o.shutdown(), o.security(), o.modded(),
        o.translation(), o.status(), new MetricsSettings(Optional.empty(), Optional.empty(), OptionalInt.of(queryPort)), o.updates(),
        o.messaging(), o.routing(), o.bans(), o.permissions());
    ConduitConfiguration configuration = new ConduitConfiguration(base.listener(), base.maxFrameBytes(), base.forwardingMode(),
        base.forwardingSecretFile(), base.backends(), base.initialBackends(), base.fallbackBackends(), base.authentication(),
        base.forwardedPlayerAddress(), ops, false, ForcedHosts.of(Map.of("Lobby.Example.com", List.of("lobby"))), -1);
    MinecraftProxy proxy = new MinecraftProxy(configuration, Authenticators.create(configuration.authentication()), RsaKeys.generate(), plugins);
    try {
      signals.clear();
      proxy.runtime().pluginRuntime().loadAll();
      proxy.runtime().started();
      require(waitFor(() -> signals.stream().anyMatch(signal -> signal.startsWith("query:")), 10_000), "the plugin initialised: " + signals);
      require(signals.containsAll(List.of("provider:true", "executor:true", "channels:true", "built:true")),
          "a Provider, the plugin's executor, the ChannelRegistrar and a class Guice builds are injected: " + signals);
      require(signals.contains("forced:{lobby.example.com=[lobby]}"), "the forced hosts, as Conduit matches them: " + signals);
      require(signals.contains("query:true:true"), "the query is on, with its port: " + signals);

      try (DatagramSocket udp = new DatagramSocket()) {
        udp.setSoTimeout(5_000);
        InetSocketAddress to = new InetSocketAddress("127.0.0.1", queryPort);
        byte[] handshake = exchange(udp, to, request(9, null, false));
        require(handshake[0] == 9 && ByteBuffer.wrap(handshake, 1, 4).getInt() == 1, "the handshake echoes the session");
        int token = Integer.parseInt(strings(handshake, 5).getFirst());
        List<String> basic = strings(exchange(udp, to, request(0, token, false)), 5);
        require(basic.subList(0, 5).equals(List.of("conduit motd", "SMP", "gapmap", "0", "77")),
            "the basic stat: MOTD, game type, the plugin's map, players, maximum: " + basic);
        List<String> full = strings(exchange(udp, to, request(0, token, true)), 16);
        int plugin = full.indexOf("plugins");
        require(plugin >= 0 && full.get(plugin + 1).endsWith(": gaps 1.0") && full.get(full.indexOf("map") + 1).equals("gapmap"),
            "the full stat carries the plugin's answer: " + full);
        require(signals.containsAll(List.of("queried:BASIC", "queried:FULL")), "ProxyQueryEvent for each stat: " + signals);
        udp.setSoTimeout(1_000);
        boolean answered = true;
        try { exchange(udp, to, request(0, token + 1, false)); } catch (SocketTimeoutException silent) { answered = false; }
        require(!answered, "a stat with a wrong token gets no answer");
      }
    } finally {
      proxy.close();
    }
  }

  /** The plugin's own cookies go through CookieStoreEvent and CookieRequestEvent, whose results are applied. */
  private static void cookieEventsDecide(Path plugins, Queue<String> signals) throws Exception {
    signals.clear();
    try (ModernBackend lobby = new ModernBackend(767);
         Proxy proxy = new Proxy(List.of(lobby.server()), AuthenticationSettings.offline(), null, plugins, ForwardingMode.NONE, Optional.empty());
         ModernClient client = ModernClient.open(767, proxy.port(), "VGap")) {
      require(waitFor(() -> proxy.runtime.plugins().plugin("gaps").isPresent(), 10_000), "gaps enabled");
      client.playing(proxy);
      proxy.runtime.commands().execute(proxy.runtime.console(), "gaps VGap");
      require(waitFor(() -> signals.stream().anyMatch(signal -> signal.startsWith("cookie:")), 10_000), "an answer: " + signals);
      Thread.sleep(300);
      require(Arrays.equals(client.cookies.get("gaps:k"), new byte[] {7}) && Arrays.equals(client.cookies.get("gaps:other"), new byte[] {8}),
          "the data CookieStoreEvent gave was stored: " + client.cookies.keySet());
      require(!client.cookies.containsKey("gaps:deny"), "a handled store never reached the client");
      List<String> answers = signals.stream().filter(signal -> signal.startsWith("cookie:")).toList();
      require(answers.equals(List.of("cookie:gaps:other:[8]")), "the request went out under the key CookieRequestEvent gave, and the handled one not at all: " + answers);
    }
  }

  /** A 1.8 Forge client's FML|HS mod list becomes getModInfo and PlayerModInfoEvent, and still reaches the backend. */
  private static void aLegacyForgeModListIsRead(Path plugins, Queue<String> signals) throws Exception {
    signals.clear();
    try (Backend lobby = new Backend("lobby");
         Proxy proxy = new Proxy(lobby, AuthenticationSettings.offline(), null, plugins);
         Client client = Client.join(proxy.port(), "Forger")) {
      require(waitFor(() -> proxy.runtime.player("Forger").isPresent(), 10_000), "Forger joined");
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        output.writeByte(2);
        MinecraftOutput.varInt(output, 2);
        MinecraftOutput.string(output, "forge"); MinecraftOutput.string(output, "10.13.4.1614");
        MinecraftOutput.string(output, "jei"); MinecraftOutput.string(output, "2.28");
      }
      int pluginIn = ProtocolDefinition.forVersion(47).id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_PLUGIN_MESSAGE);
      client.send(NativeApiTests.pluginMessage(pluginIn, "FML|HS", bytes.toByteArray()));
      require(waitFor(() -> signals.contains("mods:Forger:FML:forge@10.13.4.1614,jei@2.28:true"), 10_000), "PlayerModInfoEvent: " + signals);
      require(lobby.await(packet -> NativeApiTests.channelOf(packet).equals("FML|HS")
          && Arrays.equals(NativeApiTests.dataOf(packet), bytes.toByteArray())), "the mod list reached the backend untouched");
    }
  }

  // --- GameSpy 4 ----------------------------------------------------------------------------------

  private static byte[] request(int type, Integer token, boolean full) {
    ByteBuffer buffer = ByteBuffer.allocate(full ? 15 : token == null ? 7 : 11);
    buffer.put((byte) 0xFE).put((byte) 0xFD).put((byte) type).putInt(1);
    if (token != null) buffer.putInt(token);
    return buffer.array();
  }
  private static byte[] exchange(DatagramSocket udp, InetSocketAddress to, byte[] request) throws Exception {
    udp.send(new DatagramPacket(request, request.length, to));
    DatagramPacket reply = new DatagramPacket(new byte[4096], 4096);
    udp.receive(reply);
    return Arrays.copyOf(reply.getData(), reply.getLength());
  }
  /** The NUL-terminated strings from {@code offset} on; the basic stat's two port bytes read as part of one. */
  private static List<String> strings(byte[] data, int offset) {
    List<String> strings = new ArrayList<>();
    int start = offset;
    for (int index = offset; index < data.length; index++) {
      if (data[index] != 0) continue;
      strings.add(new String(data, start, index - start, StandardCharsets.UTF_8));
      start = index + 1;
    }
    return strings;
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
