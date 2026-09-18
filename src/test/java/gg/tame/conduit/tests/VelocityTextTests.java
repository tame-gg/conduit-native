// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.auth.Authenticators;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.crypto.RsaKeys;
import gg.tame.conduit.network.MinecraftProxy;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.text.ComponentCodec;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Formatted Adventure text from a Velocity plugin, end to end: a plugin compiled against the real
 * velocity-api sends {@link TextFidelityTests#SAMPLE}'s Adventure twin as chat and as its server-list
 * description. A scripted 1.12.2 client gets it downsampled as JSON, a scripted 1.20.4 client gets
 * the RGB colour and fallback as network NBT, and each pinging release gets its own form. The
 * adapter's mapping is also checked both ways against the native sample.
 */
public final class VelocityTextTests {
  public static void main(String[] a) throws Exception { run(); }

  private static final String PLUGIN = """
      package vtext;
      import com.velocitypowered.api.command.SimpleCommand;
      import com.velocitypowered.api.event.Subscribe;
      import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
      import com.velocitypowered.api.event.proxy.ProxyPingEvent;
      import com.velocitypowered.api.plugin.Plugin;
      import com.velocitypowered.api.proxy.ProxyServer;
      import javax.inject.Inject;
      import net.kyori.adventure.text.Component;
      import net.kyori.adventure.text.event.ClickEvent;
      import net.kyori.adventure.text.event.HoverEvent;
      import net.kyori.adventure.text.format.NamedTextColor;
      import net.kyori.adventure.text.format.TextColor;
      import net.kyori.adventure.text.format.TextDecoration;

      @Plugin(id = "vtext", name = "VText", version = "1.0")
      public final class VText {
      """ + VelocityCompatTests.SIGNAL_METHOD + """
        private final ProxyServer proxy;
        @Inject public VText(ProxyServer proxy) { this.proxy = proxy; }

        static Component rich() {
          return Component.text().content("Hi").color(TextColor.color(0x123456))
              .decoration(TextDecoration.UNDERLINED, true).decoration(TextDecoration.ITALIC, false)
              .insertion("ins").clickEvent(ClickEvent.openUrl("https://example.com"))
              .hoverEvent(HoverEvent.showText(Component.text("tip", NamedTextColor.GOLD)))
              .append(Component.translatable().key("chat.type.text").fallback("fb")
                  .arguments(Component.text("A"), Component.text("B"))
                  .decoration(TextDecoration.STRIKETHROUGH, true).clickEvent(ClickEvent.copyToClipboard("copied")))
              .build();
        }

        @Subscribe
        public void init(ProxyInitializeEvent event) {
          proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("vtext").plugin(this).build(),
              (SimpleCommand) invocation -> invocation.source().sendMessage(rich()));
          signal("vtext-ready");
        }

        @Subscribe
        public void ping(ProxyPingEvent event) {
          event.setPing(event.getPing().asBuilder().description(rich()).build());
        }
      }
      """;

  /** The plugin's component, built here too, to check the adapter's mapping against the native sample. */
  static Component rich() {
    return Component.text().content("Hi").color(TextColor.color(0x123456))
        .decoration(TextDecoration.UNDERLINED, true).decoration(TextDecoration.ITALIC, false)
        .insertion("ins").clickEvent(ClickEvent.openUrl("https://example.com"))
        .hoverEvent(HoverEvent.showText(Component.text("tip", NamedTextColor.GOLD)))
        .append(Component.translatable().key("chat.type.text").fallback("fb")
            .arguments(Component.text("A"), Component.text("B"))
            .decoration(TextDecoration.STRIKETHROUGH, true).clickEvent(ClickEvent.copyToClipboard("copied")))
        .build();
  }

  public static void run() throws Exception {
    adapterMapsBothWays();
    VelocityCompatTests.installSignals();
    Path root = TempFiles.dir("velocity-text");
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    VelocityCompatTests.jar(VelocityCompatTests.compile(root, "vtext.VText", PLUGIN, List.of(), true), plugins.resolve("VText.jar"), null);
    aLegacyClientGetsItDownsampled(plugins);
    aModernClientGetsEverything(plugins);
    System.out.println("VelocityTextTests OK");
  }

  /** Texts, the adapter's own mapping, reached by reflection since plugins and tests cannot link to it. */
  private static void adapterMapsBothWays() throws Exception {
    Class<?> texts = Class.forName("gg.tame.conduit.compat.velocity.Texts");
    var toConduit = texts.getDeclaredMethod("toConduit", Component.class);
    var toAdventure = texts.getDeclaredMethod("toAdventure", gg.tame.conduit.api.text.Text.class);
    toConduit.setAccessible(true);
    toAdventure.setAccessible(true);
    Object mapped = toConduit.invoke(null, rich());
    require(mapped.equals(TextFidelityTests.SAMPLE), "Adventure to Text keeps everything: "
        + gg.tame.conduit.text.TextCodec.toJson((gg.tame.conduit.api.text.Text) mapped, 776));
    require(toAdventure.invoke(null, TextFidelityTests.SAMPLE).equals(rich()), "and Text to Adventure gives the same component back");
    Component named = Component.text("n", NamedTextColor.RED).decoration(TextDecoration.OBFUSCATED, true)
        .clickEvent(ClickEvent.changePage(2)).append(Component.keybind("key.jump"));
    gg.tame.conduit.api.text.Text text = (gg.tame.conduit.api.text.Text) toConduit.invoke(null, named);
    require(text.color() == gg.tame.conduit.api.text.TextColor.RED, "a named colour stays named");
    require(text.clickEvent().equals(gg.tame.conduit.api.text.Text.ClickEvent.changePage(2)), "a page click");
    require(text.children().getFirst().content().equals("key.jump"), "a keybind becomes its plain rendering");
    require(toConduit.invoke(null, Component.text("x").clickEvent(ClickEvent.openFile("a.txt"))).equals(gg.tame.conduit.api.text.Text.of("x")),
        "an open-file click is dropped");
  }

  /** 1.12.2 client and backends: chat and the server list are JSON, RGB as its nearest named colour. */
  private static void aLegacyClientGetsItDownsampled(Path plugins) throws Exception {
    try (DisplayApiTests.Server1122 lobby = new DisplayApiTests.Server1122()) {
      ConduitConfiguration configuration = VelocityCompatTests.configuration(List.of(lobby.server("lobby")));
      try (MinecraftProxy proxy = new MinecraftProxy(configuration, Authenticators.create(configuration.authentication()), RsaKeys.generate(), plugins)) {
        DisplayApiTests.platform("velocity-text-serve", () -> { try { proxy.serve(); } catch (IOException ignored) { } });
        VelocityCompatTests.awaitSignal("vtext-ready");
        String old = statusDescription(proxy.port(), 340);
        require(old.equals(TextFidelityTests.JSON_18), "a 1.12.2 ping gets the 1.12.2 form: " + old);
        String modern = statusDescription(proxy.port(), 765);
        require(modern.equals(TextFidelityTests.JSON_1194), "a 1.20.4 ping gets RGB and the fallback: " + modern);
        try (VelocityDisplayTests.Client client = VelocityDisplayTests.Client.join(proxy.port(), "Old")) {
          client.await(packet -> NativeApiTests.id(packet) == 0x23, "Join Game");
          client.chat("/vtext");
          client.await(packet -> NativeApiTests.id(packet) == 0x0F && chatJson(packet).equals(TextFidelityTests.JSON_18),
              "the plugin's text as 1.12.2 chat: " + client.received(packet -> NativeApiTests.id(packet) == 0x0F).stream()
                  .map(VelocityTextTests::chatJson).toList());
        }
      }
    }
  }

  /** 1.20.4 client and backend: the same text as network NBT, RGB colour and fallback kept. */
  private static void aModernClientGetsEverything(Path plugins) throws Exception {
    ProtocolDefinition p = ProtocolDefinition.forVersion(765);
    int configOut = p.id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_PLUGIN_MESSAGE);
    byte finishOut = (byte) p.id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_FINISH);
    byte finishIn = (byte) p.id(ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_FINISH);
    int joinGame = p.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN);
    int systemChat = p.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SYSTEM_CHAT);
    int chatCommand = p.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT_COMMAND);
    try (ServerSocket listener = new ServerSocket(0)) {
      ModLoaderTests.Mock backend = new ModLoaderTests.Mock(listener, "text", new byte[0], configOut, finishOut, finishIn);
      DisplayApiTests.platform("velocity-text-backend", backend);
      ConduitConfiguration configuration = VelocityCompatTests.configuration(
          List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", listener.getLocalPort()))));
      try (MinecraftProxy proxy = new MinecraftProxy(configuration, Authenticators.create(configuration.authentication()), RsaKeys.generate(), plugins)) {
        DisplayApiTests.platform("velocity-text-serve-modern", () -> { try { proxy.serve(); } catch (IOException ignored) { } });
        VelocityCompatTests.awaitCount("vtext-ready", 2);
        try (Socket client = new Socket("127.0.0.1", proxy.port())) {
          client.setSoTimeout(15_000);
          InputStream in = client.getInputStream();
          OutputStream out = client.getOutputStream();
          MinecraftFrames.write(out, new Handshake(765, "localhost", 25565, 2).encode());
          MinecraftFrames.write(out, ModLoaderTests.loginStart());
          require(MinecraftFrames.read(in, 8192)[0] == 0x02, "Login Success");
          MinecraftFrames.write(out, new byte[] {0x03});
          ModLoaderTests.readConfiguration(in, finishOut);
          MinecraftFrames.write(out, new byte[] {finishIn});
          while (MinecraftFrames.read(in, 1 << 20)[0] != (byte) joinGame) { }
          MinecraftFrames.write(out, ModLoaderTests.command(chatCommand, "vtext"));
          while (true) {
            byte[] packet = MinecraftFrames.read(in, 1 << 20);
            if (packet[0] != (byte) systemChat) continue;
            try (DataInputStream body = new DataInputStream(new ByteArrayInputStream(packet, 1, packet.length - 1))) {
              String json = ComponentCodec.nbtToJson(body);
              require(json.equals(TextFidelityTests.JSON_1194), "the plugin's text as 1.20.4 NBT chat: " + json);
            }
            break;
          }
        }
      }
    }
  }

  /** The description of the proxy's status answer to a client of {@code protocol}, as JSON. */
  private static String statusDescription(int port, int protocol) throws IOException {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(10_000);
      MinecraftFrames.write(socket.getOutputStream(), new Handshake(protocol, "localhost", port, 1).encode());
      MinecraftFrames.write(socket.getOutputStream(), new byte[] {0x00});
      byte[] response = MinecraftFrames.read(socket.getInputStream(), 1 << 16);
      String json = MinecraftInput.string(new DataInputStream(new ByteArrayInputStream(response, 1, response.length - 1)), 1 << 16);
      return ComponentCodec.toJson(((Map<?, ?>) ComponentCodec.parseJson(json)).get("description"));
    }
  }

  private static String chatJson(byte[] packet) {
    try { return MinecraftInput.string(DisplayApiTests.body(packet), 1 << 16); } catch (IOException unreadable) { return ""; }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
