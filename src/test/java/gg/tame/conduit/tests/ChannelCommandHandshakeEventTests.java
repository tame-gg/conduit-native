// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.NativeApiTests.CHAT_IN;
import static gg.tame.conduit.tests.NativeApiTests.P47;
import static gg.tame.conduit.tests.NativeApiTests.id;
import static gg.tame.conduit.tests.NativeApiTests.waitFor;

import gg.tame.conduit.api.command.CommandManager;
import gg.tame.conduit.api.event.command.PostCommandEvent;
import gg.tame.conduit.api.event.player.PlayerChannelRegisterEvent;
import gg.tame.conduit.api.event.player.PlayerChannelUnregisterEvent;
import gg.tame.conduit.api.event.player.PlayerPostLoginEvent;
import gg.tame.conduit.api.event.proxy.ConnectionHandshakeEvent;
import gg.tame.conduit.config.AuthenticationSettings;
import gg.tame.conduit.modded.RegisteredChannels;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PluginMessage;
import gg.tame.conduit.tests.NativeApiTests.Backend;
import gg.tame.conduit.tests.NativeApiTests.Client;
import gg.tame.conduit.tests.NativeApiTests.Fixture;
import gg.tame.conduit.tests.NativeApiTests.TestPlugin;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Plugin channel (un)registration, finished commands and handshakes, as native events and as
 * Velocity's PlayerChannel(Un)RegisterEvent, PostCommandInvocationEvent and ConnectionHandshakeEvent:
 * a scripted 1.8 client and backend through a live proxy, and a compiled Velocity plugin.
 */
public final class ChannelCommandHandshakeEventTests {
  public static void main(String[] a) throws Exception { run(); }

  public static void run() throws Exception {
    channelsCommandsAndHandshakesNatively();
    velocityPluginsHearThemToo();
    System.out.println("ChannelCommandHandshakeEventTests OK");
  }

  private static final int PLUGIN_IN = P47.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_PLUGIN_MESSAGE);

  private static void channelsCommandsAndHandshakesNatively() throws Exception {
    try (Backend lobby = new Backend("lobby"); Fixture fixture = new Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"))) {
      var owner = new TestPlugin("events-probe");
      fixture.runtime.commands().register(owner, CommandManager.Command.builder("ok").handler((source, arguments) -> { }).build());
      fixture.runtime.commands().register(owner, CommandManager.Command.builder("boom").handler((source, arguments) -> {
        throw new IllegalStateException("on purpose");
      }).build());
      require(ping(fixture.port(), "list.example"), "the ping was answered");
      try (Client client = Client.join(fixture.port(), "Channeler")) {
        require(fixture.recorder.await(PlayerPostLoginEvent.class, 1), "joined");
        List<ConnectionHandshakeEvent> handshakes = fixture.recorder.of(ConnectionHandshakeEvent.class);
        require(handshakes.size() == 2, "a handshake each for the ping and the login: " + handshakes);
        require(handshakes.get(0).intent() == ConnectionHandshakeEvent.Intent.STATUS && handshakes.get(0).virtualHost().getHostString().equals("list.example")
            && handshakes.get(0).protocolVersion() == 47, "the ping's: " + handshakes.get(0));
        require(handshakes.get(1).intent() == ConnectionHandshakeEvent.Intent.LOGIN && handshakes.get(1).virtualHost().getHostString().equals("localhost")
            && handshakes.get(1).virtualHost().getPort() == 25565 && handshakes.get(1).remoteAddress().getAddress().isLoopbackAddress(), "the login's: " + handshakes.get(1));

        // Registration: the event, and the packet on to the backend as the client sent it.
        byte[] register = new PluginMessage("REGISTER", "WECUI\0MyMod\0\0 \0".getBytes(StandardCharsets.UTF_8)).encode(PLUGIN_IN);
        client.send(register);
        require(lobby.await(packet -> Arrays.equals(packet, register)), "the backend got the REGISTER byte for byte");
        require(fixture.recorder.await(PlayerChannelRegisterEvent.class, 1), "registered");
        PlayerChannelRegisterEvent registered = fixture.recorder.of(PlayerChannelRegisterEvent.class).getFirst();
        require(registered.channels().equals(List.of("WECUI", "MyMod")) && registered.player().username().equals("Channeler"),
            "the names it gave, blank ones left out: " + registered.channels());
        byte[] unregister = new PluginMessage("UNREGISTER", "MyMod".getBytes(StandardCharsets.UTF_8)).encode(PLUGIN_IN);
        client.send(unregister);
        require(lobby.await(packet -> Arrays.equals(packet, unregister)), "the backend got the UNREGISTER byte for byte");
        require(fixture.recorder.await(PlayerChannelUnregisterEvent.class, 1)
            && fixture.recorder.of(PlayerChannelUnregisterEvent.class).getFirst().channels().equals(List.of("MyMod")), "unregistered");

        // A hostile list: most of a megabyte of names, and one far too long. One bounded event, and on it goes.
        StringBuilder names = new StringBuilder("x".repeat(5_000)).append('\0');
        for (int index = 0; names.length() < 900_000; index++) names.append('c').append(index).append('\0');
        byte[] flood = new PluginMessage("REGISTER", names.toString().getBytes(StandardCharsets.UTF_8)).encode(PLUGIN_IN);
        client.send(flood);
        require(lobby.await(packet -> Arrays.equals(packet, flood)), "the backend got the hostile REGISTER byte for byte");
        require(fixture.recorder.await(PlayerChannelRegisterEvent.class, 2), "one more event");
        List<String> flooded = fixture.recorder.of(PlayerChannelRegisterEvent.class).get(1).channels();
        require(flooded.size() == RegisteredChannels.MAX_CHANNELS && flooded.getFirst().equals("c0"),
            "at most " + RegisteredChannels.MAX_CHANNELS + " names, the over-long one left out: " + flooded.size());
        require(fixture.recorder.of(PlayerChannelRegisterEvent.class).size() == 2, "and only one event for it");

        // Commands: the proxy's, one that throws, and one the backend gets.
        client.send(NativeApiTests.chat("/ok a b"));
        client.send(NativeApiTests.chat("/boom"));
        byte[] backendCommand = NativeApiTests.chat("/backendonly x");
        client.send(backendCommand);
        require(lobby.await(packet -> Arrays.equals(packet, backendCommand)), "the backend got its command byte for byte");
        fixture.runtime.commands().execute(fixture.runtime.console(), "ok from console");
        require(waitFor(() -> fixture.recorder.of(PostCommandEvent.class).size() >= 4, 10_000), "four finished commands");
        List<String> finished = fixture.recorder.of(PostCommandEvent.class).stream()
            .map(event -> event.command() + "|" + event.result() + "|" + event.source().username()).toList();
        require(finished.equals(List.of("ok a b|EXECUTED|Channeler", "boom|EXCEPTION|Channeler", "backendonly x|FORWARDED|Channeler",
            "ok from console|EXECUTED|" + fixture.runtime.console().username())), "each with its outcome: " + finished);
        require(lobby.received(packet -> id(packet) == CHAT_IN).size() == 1, "and only the backend's command reached it");
      }
    }
  }

  private static final String EVENTSV = """
      package eventsv;
      import com.mojang.brigadier.arguments.IntegerArgumentType;
      import com.mojang.brigadier.builder.LiteralArgumentBuilder;
      import com.mojang.brigadier.builder.RequiredArgumentBuilder;
      import com.velocitypowered.api.command.BrigadierCommand;
      import com.velocitypowered.api.command.CommandSource;
      import com.velocitypowered.api.command.SimpleCommand;
      import com.velocitypowered.api.event.Subscribe;
      import com.velocitypowered.api.event.command.PostCommandInvocationEvent;
      import com.velocitypowered.api.event.connection.ConnectionHandshakeEvent;
      import com.velocitypowered.api.event.player.PlayerChannelRegisterEvent;
      import com.velocitypowered.api.event.player.PlayerChannelUnregisterEvent;
      import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
      import com.velocitypowered.api.plugin.Plugin;
      import com.velocitypowered.api.proxy.Player;
      import com.velocitypowered.api.proxy.ProxyServer;
      import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
      import javax.inject.Inject;

      @Plugin(id = "eventsv", name = "EventsV", version = "1")
      public final class EventsV {
        @SuppressWarnings("unchecked")
        static void signal(String value) { ((java.util.Queue<String>) System.getProperties().get("events.test.signals")).add(value); }
        private final ProxyServer proxy;
        @Inject public EventsV(ProxyServer proxy) { this.proxy = proxy; }
        @Subscribe public void init(ProxyInitializeEvent event) {
          proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("vok").plugin(this).build(), (SimpleCommand) invocation -> { });
          proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("vboom").plugin(this).build(), (SimpleCommand) invocation -> {
            throw new IllegalStateException("on purpose");
          });
          proxy.getCommandManager().register(new BrigadierCommand(LiteralArgumentBuilder.<CommandSource>literal("vbrig")
              .then(RequiredArgumentBuilder.<CommandSource, Integer>argument("n", IntegerArgumentType.integer()).executes(context -> 1))));
        }
        @Subscribe public void handshake(ConnectionHandshakeEvent event) {
          signal("handshake:" + event.getIntent() + ":" + event.getConnection().getProtocolVersion().getProtocol() + ":"
              + event.getConnection().getVirtualHost().orElseThrow().getHostString() + ":" + event.getConnection().getProtocolState());
        }
        @Subscribe public void register(PlayerChannelRegisterEvent event) {
          signal("register:" + event.getPlayer().getUsername() + ":" + event.getChannels().stream().map(ChannelIdentifier::getId).toList());
        }
        @Subscribe public void unregister(PlayerChannelUnregisterEvent event) {
          signal("unregister:" + event.getPlayer().getUsername() + ":" + event.getChannels().stream().map(ChannelIdentifier::getId).toList());
        }
        @Subscribe public void finished(PostCommandInvocationEvent event) {
          signal("post:" + event.getCommand() + ":" + event.getResult() + ":" + (event.getCommandSource() instanceof Player player ? player.getUsername() : "console"));
        }
      }
      """;

  private static void velocityPluginsHearThemToo() throws Exception {
    Queue<String> signals = new ConcurrentLinkedQueue<>();
    System.getProperties().put("events.test.signals", signals);
    try {
      Path plugins = LoginFlowTests.compiledPlugin("eventsv.EventsV", EVENTSV);
      try (Backend lobby = new Backend("lobby");
           Fixture fixture = new Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"), plugins, AuthenticationSettings.offline(), null)) {
        require(waitFor(() -> fixture.runtime.plugins().plugin("eventsv").isPresent(), 10_000), "eventsv enabled");
        require(ping(fixture.port(), "velocity.example"), "the ping was answered");
        require(waitFor(() -> signals.contains("handshake:STATUS:47:velocity.example:HANDSHAKE"), 10_000), "the ping's handshake: " + signals);
        try (Client client = Client.join(fixture.port(), "VChan")) {
          require(fixture.recorder.await(PlayerPostLoginEvent.class, 1), "joined");
          require(waitFor(() -> signals.contains("handshake:LOGIN:47:localhost:HANDSHAKE"), 10_000), "the login's handshake: " + signals);
          byte[] register = new PluginMessage("REGISTER", "WECUI\0bungeecord:main".getBytes(StandardCharsets.UTF_8)).encode(PLUGIN_IN);
          client.send(register);
          require(lobby.await(packet -> Arrays.equals(packet, register)), "the backend got the REGISTER byte for byte");
          require(waitFor(() -> signals.contains("register:VChan:[WECUI, bungeecord:main]"), 10_000), "a legacy and a namespaced channel: " + signals);
          client.send(new PluginMessage("UNREGISTER", "WECUI".getBytes(StandardCharsets.UTF_8)).encode(PLUGIN_IN));
          require(waitFor(() -> signals.contains("unregister:VChan:[WECUI]"), 10_000), "unregistered: " + signals);

          client.send(NativeApiTests.chat("/vok x"));
          client.send(NativeApiTests.chat("/vboom"));
          client.send(NativeApiTests.chat("/vbrig notanumber"));
          byte[] backendCommand = NativeApiTests.chat("/backendonly y");
          client.send(backendCommand);
          require(lobby.await(packet -> Arrays.equals(packet, backendCommand)), "the backend got its command byte for byte");
          fixture.runtime.commands().execute(fixture.runtime.console(), "vok z");
          List<String> expected = List.of("post:vok x:EXECUTED:VChan", "post:vboom:EXCEPTION:VChan", "post:vbrig notanumber:SYNTAX_ERROR:VChan",
              "post:backendonly y:FORWARDED:VChan", "post:vok z:EXECUTED:console");
          require(waitFor(() -> signals.containsAll(expected), 10_000), "each outcome, once the body ran: " + signals);
          require(signals.stream().filter(signal -> signal.startsWith("post:")).count() == expected.size(), "and nothing twice: " + signals);
        }
      }
    } finally {
      System.getProperties().remove("events.test.signals");
    }
  }

  /** A raw 1.8 server-list ping; true once the proxy answered it. */
  private static boolean ping(int port, String host) throws Exception {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(10_000);
      MinecraftFrames.write(socket.getOutputStream(), new Handshake(47, host, 25565, 1).encode());
      MinecraftFrames.write(socket.getOutputStream(), new byte[] {0});
      boolean answered = id(MinecraftFrames.read(socket.getInputStream(), 1 << 20)) == 0;
      MinecraftFrames.write(socket.getOutputStream(), NativeApiTests.packet(1, output -> output.writeLong(42)));
      return answered && id(MinecraftFrames.read(socket.getInputStream(), 64)) == 1;
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
