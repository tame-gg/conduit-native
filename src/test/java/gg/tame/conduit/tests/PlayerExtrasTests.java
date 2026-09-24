// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.NativeApiTests.CHAT_IN;
import static gg.tame.conduit.tests.NativeApiTests.id;
import static gg.tame.conduit.tests.NativeApiTests.packet;
import static gg.tame.conduit.tests.NativeApiTests.waitFor;

import gg.tame.conduit.api.event.command.CommandExecuteEvent;
import gg.tame.conduit.api.event.player.PlayerChatEvent;
import gg.tame.conduit.api.event.player.PlayerCookieReceiveEvent;
import gg.tame.conduit.api.event.player.PlayerPostLoginEvent;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.api.player.Player.ChatCompletions;
import gg.tame.conduit.api.player.ServerLink;
import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.config.AuthenticationSettings;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ForwardingMode;
import gg.tame.conduit.login.LoginStart;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.PlayerApiPackets;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.tests.LoginFlowTests.Proxy;
import gg.tame.conduit.tests.NativeApiTests.Backend;
import gg.tame.conduit.tests.NativeApiTests.Client;
import gg.tame.conduit.tests.NativeApiTests.Fixture;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Predicate;

/**
 * Player.spoofChatInput, custom chat completions, server links and cookies: their packets and ids for
 * each release, scripted 1.8, 1.20.4, 1.20.5 and 1.21 clients and servers through a live proxy, and a
 * compiled Velocity plugin using Velocity's API for each.
 */
public final class PlayerExtrasTests {
  public static void main(String[] a) throws Exception { run(); }

  public static void run() throws Exception {
    packetsForEachRelease();
    aLegacyClientSaysWhatItIsGivenAndIsSentNothingNew();
    aModernClientSpeaksUnsigned();
    customCompletionsReachA1204Client();
    serverLinksReachA121Client();
    cookiesStayBetweenTheProxyAndTheClient();
    velocityPluginsUseAllOfIt();
    System.out.println("PlayerExtrasTests OK");
  }

  // --- packets -------------------------------------------------------------------------------------

  private static void packetsForEachRelease() throws Exception {
    // Ids from minecraft-data (tools/packetids.py); 776 carries 775's.
    idsAre(PacketKind.PLAY_CHAT_SUGGESTIONS, ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT,
        Map.of(759, -1, 760, 0x15, 761, 0x14, 762, 0x16, 763, 0x16, 765, 0x17, 766, 0x18, 767, 0x18, 770, 0x17, 776, 0x17));
    idsAre(PacketKind.PLAY_SERVER_LINKS, ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT,
        Map.of(766, -1, 767, 0x7B, 768, 0x82, 772, 0x82, 773, 0x87, 775, 0x89, 776, 0x89));
    idsAre(PacketKind.CONFIGURATION_SERVER_LINKS, ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT,
        Map.of(766, -1, 767, 0x10, 775, 0x10, 776, 0x10));
    idsAre(PacketKind.PLAY_STORE_COOKIE, ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT,
        Map.of(765, -1, 766, 0x6B, 767, 0x6B, 768, 0x72, 770, 0x71, 773, 0x76, 775, 0x78, 776, 0x78));
    idsAre(PacketKind.PLAY_COOKIE_REQUEST, ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT,
        Map.of(765, -1, 766, 0x16, 769, 0x16, 770, 0x15, 776, 0x15));
    idsAre(PacketKind.PLAY_COOKIE_RESPONSE, ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER,
        Map.of(765, -1, 766, 0x11, 768, 0x13, 771, 0x14, 775, 0x15, 776, 0x15));
    idsAre(PacketKind.CONFIGURATION_COOKIE_RESPONSE, ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER,
        Map.of(765, -1, 766, 0x01, 776, 0x01));

    require(Arrays.equals(PlayerApiPackets.chatSuggestions(0x17, 2, List.of("ab")), new byte[] {0x17, 2, 1, 2, 'a', 'b'}), "completions");
    byte[] links = PlayerApiPackets.serverLinks(0x7B, 767, List.of(ServerLink.of(ServerLink.Type.WEBSITE, URI.create("https://a.b")),
        ServerLink.of(Text.of("Docs"), URI.create("https://c.d"))));
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(links))) {
      require(MinecraftInput.varInt(input) == 0x7B && MinecraftInput.varInt(input) == 2, "two links");
      require(input.readBoolean() && MinecraftInput.varInt(input) == 6 && MinecraftInput.string(input, 100).equals("https://a.b"), "a known type, WEBSITE being 6");
      require(!input.readBoolean(), "then a label");
      byte[] rest = input.readAllBytes();
      require(new String(rest, StandardCharsets.UTF_8).contains("Docs") && new String(rest, StandardCharsets.UTF_8).endsWith("https://c.d"),
          "the label as text, then its URL");
    }
    require(Arrays.equals(PlayerApiPackets.storeCookie(0x6B, "a:b", new byte[] {9}), new byte[] {0x6B, 3, 'a', ':', 'b', 1, 9}), "store");
    require(Arrays.equals(PlayerApiPackets.cookieRequest(0x16, "a:b"), new byte[] {0x16, 3, 'a', ':', 'b'}), "request");
    ProtocolDefinition p766 = ProtocolDefinition.forVersion(766);
    var answered = PlayerApiPackets.cookieResponse(p766, ConnectionState.PLAY, new byte[] {0x11, 3, 'a', ':', 'b', 1, 1, 9});
    require(answered.isPresent() && answered.get().key().equals("a:b") && Arrays.equals(answered.get().data(), new byte[] {9}), "an answer");
    var none = PlayerApiPackets.cookieResponse(p766, ConnectionState.CONFIGURATION, new byte[] {0x01, 3, 'a', ':', 'b', 0});
    require(none.isPresent() && none.get().data() == null, "an answer without a cookie");
    require(PlayerApiPackets.cookieResponse(p766, ConnectionState.PLAY, new byte[] {0x12, 3, 'a', ':', 'b', 0}).isEmpty(), "another packet");
    require(PlayerApiPackets.cookieKey("token").equals("minecraft:token") && PlayerApiPackets.cookieKey("my_plugin:a/b.c").equals("my_plugin:a/b.c"), "keys");
    for (String bad : new String[] {"Upper:case", "a b", "a:b:c", ":x", "x:"}) {
      boolean refused = false;
      try { PlayerApiPackets.cookieKey(bad); } catch (IllegalArgumentException expected) { refused = true; }
      require(refused, "not a key: " + bad);
    }
  }
  private static void idsAre(PacketKind kind, ConnectionState state, PacketDirection direction, Map<Integer, Integer> expected) {
    expected.forEach((protocol, id) -> {
      ProtocolDefinition definition = ProtocolDefinition.forVersion(protocol);
      int actual = definition.defines(state, direction, kind) ? definition.id(state, direction, kind) : -1;
      require(actual == id, kind + " on " + protocol + ": expected " + id + ", got " + actual);
    });
  }

  // --- 1.8 ----------------------------------------------------------------------------------------

  private static void aLegacyClientSaysWhatItIsGivenAndIsSentNothingNew() throws Exception {
    try (Backend lobby = new Backend("lobby"); Fixture fixture = new Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"))) {
      try (Client client = Client.join(fixture.port(), "Talker")) {
        require(fixture.recorder.await(PlayerPostLoginEvent.class, 1), "joined");
        Player player = fixture.runtime.player("Talker").orElseThrow();
        require(player.spoofChatInput("hello there"), "chat sent");
        require(player.spoofChatInput("/spawn now"), "a command sent");
        String longest = "x".repeat(100);
        require(player.spoofChatInput(longest), "100 characters, all a 1.8 chat box takes");
        refused(IllegalArgumentException.class, () -> player.spoofChatInput(longest + "x"), "101 characters");
        for (String line : List.of("hello there", "/spawn now", longest)) {
          require(lobby.await(packet -> id(packet) == CHAT_IN && chatLine(packet).equals(line)), "the backend got " + line);
        }
        require(fixture.recorder.of(PlayerChatEvent.class).isEmpty() && fixture.recorder.of(CommandExecuteEvent.class).isEmpty(),
            "the proxy's own chat and command events heard none of it");

        require(!player.updateCustomChatCompletions(ChatCompletions.ADD, List.of("a")), "no completions before 1.19.1");
        require(!player.setServerLinks(List.of(ServerLink.of(ServerLink.Type.NEWS, URI.create("https://x.y")))), "no links before 1.21");
        require(!player.storeCookie("a:b", new byte[1]) && !player.requestCookie("a:b"), "no cookies before 1.20.5");
        player.sendMessage("still here");
        require(client.await(packet -> id(packet) == NativeApiTests.CHAT_OUT), "and the session carries on");
      }
    }
  }

  // --- 1.20.4 and 1.20.5 --------------------------------------------------------------------------

  /** A 1.19+ client's chat and commands go unsigned, as SecureChatApiTests and the live-backend runs check in depth. */
  private static void aModernClientSpeaksUnsigned() throws Exception {
    try (ModernBackend lobby = new ModernBackend(766);
         Proxy proxy = new Proxy(List.of(lobby.server()), AuthenticationSettings.offline(), null, null, ForwardingMode.NONE, Optional.empty());
         ModernClient client = ModernClient.open(766, proxy.port(), "Commander")) {
      Player player = client.playing(proxy);
      require(player.spoofChatInput("/spawn now"), "the command sent");
      int command = lobby.p.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT_COMMAND);
      require(lobby.await(packet -> id(packet) == command && chatLine(packet).equals("spawn now")),
          "an unsigned Chat Command, the command alone, without its slash");
      require(player.spoofChatInput("hello"), "chat sent");
      int chat = lobby.p.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT);
      require(lobby.await(packet -> id(packet) == chat && chatLine(packet).equals("hello")), "as unsigned chat");
      refused(IllegalArgumentException.class, () -> player.spoofChatInput("/" + "x".repeat(256)), "257 characters");
      require(proxy.recorder.of(CommandExecuteEvent.class).isEmpty(), "the proxy's command event heard nothing");
    }
    try (ModernBackend lobby = new ModernBackend(765);
         Proxy proxy = new Proxy(List.of(lobby.server()), AuthenticationSettings.offline(), null, null, ForwardingMode.NONE, Optional.empty());
         ModernClient client = ModernClient.open(765, proxy.port(), "Signer")) {
      Player player = client.playing(proxy);
      require(player.spoofChatInput("/spawn"), "a 1.20.4 command sent");
      int command = lobby.p.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT_COMMAND);
      require(lobby.await(packet -> id(packet) == command && chatLine(packet).equals("spawn")), "with nothing signed and nothing new acknowledged");
      require(!player.storeCookie("a:b", new byte[1]) && !player.requestCookie("a:b") && !player.setServerLinks(List.of()),
          "no cookies or links for 1.20.4");
    }
  }

  private static void customCompletionsReachA1204Client() throws Exception {
    try (ModernBackend lobby = new ModernBackend(765);
         Proxy proxy = new Proxy(List.of(lobby.server()), AuthenticationSettings.offline(), null, null, ForwardingMode.NONE, Optional.empty());
         ModernClient client = ModernClient.open(765, proxy.port(), "Typer")) {
      Player player = client.playing(proxy);
      require(player.updateCustomChatCompletions(ChatCompletions.ADD, List.of("alpha", "beta")), "added");
      require(player.updateCustomChatCompletions(ChatCompletions.REMOVE, List.of("beta")), "removed");
      require(player.updateCustomChatCompletions(ChatCompletions.SET, List.of()), "cleared");
      List<byte[]> sent = client.await(ConnectionState.PLAY, PacketKind.PLAY_CHAT_SUGGESTIONS, 3);
      require(Arrays.equals(sent.get(0), new byte[] {0x17, 0, 2, 5, 'a', 'l', 'p', 'h', 'a', 4, 'b', 'e', 't', 'a'}), "add");
      require(Arrays.equals(sent.get(1), new byte[] {0x17, 1, 1, 4, 'b', 'e', 't', 'a'}), "remove");
      require(Arrays.equals(sent.get(2), new byte[] {0x17, 2, 0}), "set to none");
    }
  }

  // --- 1.21 ---------------------------------------------------------------------------------------

  private static void serverLinksReachA121Client() throws Exception {
    try (ModernBackend lobby = new ModernBackend(767);
         Proxy proxy = new Proxy(List.of(lobby.server()), AuthenticationSettings.offline(), null, null, ForwardingMode.NONE, Optional.empty());
         ModernClient client = ModernClient.open(767, proxy.port(), "Linker")) {
      Player player = client.playing(proxy);
      List<ServerLink> links = List.of(ServerLink.of(ServerLink.Type.BUG_REPORT, URI.create("https://bugs.example")),
          ServerLink.of(Text.of("Map"), URI.create("https://map.example")));
      require(player.setServerLinks(links), "sent");
      byte[] sent = client.await(ConnectionState.PLAY, PacketKind.PLAY_SERVER_LINKS, 1).getFirst();
      require(Arrays.equals(sent, PlayerApiPackets.serverLinks(0x7B, 767, links)), "the links, in 1.21's Play form");
      refused(NullPointerException.class, () -> ServerLink.of(ServerLink.Type.NEWS, null), "a link without a URL");
      refused(IllegalArgumentException.class, () -> new ServerLink(ServerLink.Type.NEWS, Text.of("x"), URI.create("https://x")), "both");
    }
  }

  private static void cookiesStayBetweenTheProxyAndTheClient() throws Exception {
    try (ModernBackend lobby = new ModernBackend(766);
         Proxy proxy = new Proxy(List.of(lobby.server()), AuthenticationSettings.offline(), null, null, ForwardingMode.NONE, Optional.empty());
         ModernClient client = ModernClient.open(766, proxy.port(), "Baker")) {
      Player player = client.playing(proxy);
      require(player.storeCookie("conduit:token", new byte[] {1, 2, 3}), "stored");
      require(waitFor(() -> client.cookies.containsKey("conduit:token"), 10_000), "the client keeps it");
      require(player.requestCookie("conduit:token"), "asked for it");
      require(player.requestCookie("missing"), "and for one it does not have, as minecraft:missing");
      require(proxy.recorder.await(PlayerCookieReceiveEvent.class, 2), "both answers reached the plugins");
      List<PlayerCookieReceiveEvent> answers = proxy.recorder.of(PlayerCookieReceiveEvent.class);
      require(answers.get(0).key().equals("conduit:token") && Arrays.equals(answers.get(0).data(), new byte[] {1, 2, 3})
          && answers.get(0).player().username().equals("Baker"), "the cookie, under its key");
      require(answers.get(1).key().equals("minecraft:missing") && answers.get(1).data() == null, "none under the other");

      // The backend's own cookie traffic goes through as it was, answer and all, and plugins hear nothing of it.
      lobby.send("Baker", PlayerApiPackets.storeCookie(0x6B, "server:session", "abc".getBytes(StandardCharsets.UTF_8)));
      lobby.send("Baker", PlayerApiPackets.cookieRequest(0x16, "server:session"));
      byte[] expected = packet(0x11, output -> {
        MinecraftOutput.string(output, "server:session"); output.writeBoolean(true); MinecraftOutput.varInt(output, 3); output.write("abc".getBytes(StandardCharsets.UTF_8));
      });
      require(lobby.await(packet -> Arrays.equals(packet, expected)), "the backend got the client's answer to its own request");
      require(lobby.received(packet -> id(packet) == 0x11).size() == 1, "and none of the proxy's: " + lobby.received(packet -> id(packet) == 0x11).size());
      require(proxy.recorder.of(PlayerCookieReceiveEvent.class).size() == 2, "no event for the backend's");

      refused(IllegalArgumentException.class, () -> player.storeCookie("a:b", new byte[PlayerApiPackets.COOKIE_MAX_BYTES + 1]), "over 5 KiB");
      require(player.storeCookie("a:b", new byte[PlayerApiPackets.COOKIE_MAX_BYTES]), "exactly 5 KiB");
      refused(IllegalArgumentException.class, () -> player.requestCookie("Not A Key"), "a key the client would disconnect over");
    }
  }

  // --- Velocity -----------------------------------------------------------------------------------

  private static final String EXTRASV = """
      package extrasv;
      import com.velocitypowered.api.command.SimpleCommand;
      import com.velocitypowered.api.event.Subscribe;
      import com.velocitypowered.api.event.player.CookieReceiveEvent;
      import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
      import com.velocitypowered.api.plugin.Plugin;
      import com.velocitypowered.api.proxy.Player;
      import com.velocitypowered.api.proxy.ProxyServer;
      import com.velocitypowered.api.util.ServerLink;
      import java.util.List;
      import javax.inject.Inject;
      import net.kyori.adventure.key.Key;
      import net.kyori.adventure.text.Component;

      @Plugin(id = "extrasv", name = "ExtrasV", version = "1")
      public final class ExtrasV {
        @SuppressWarnings("unchecked")
        static void signal(String value) { ((java.util.Queue<String>) System.getProperties().get("extras.test.signals")).add(value); }
        private final ProxyServer proxy;
        @Inject public ExtrasV(ProxyServer proxy) { this.proxy = proxy; }
        @Subscribe public void init(ProxyInitializeEvent event) {
          proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("extras").plugin(this).build(), (SimpleCommand) invocation -> {
            Player player = proxy.getPlayer(invocation.arguments()[0]).orElseThrow();
            try {
              switch (invocation.arguments()[1]) {
                case "all" -> {
                  player.setServerLinks(List.of(ServerLink.serverLink(ServerLink.Type.WEBSITE, "https://site.example"),
                      ServerLink.serverLink(Component.text("Rules"), "https://rules.example")));
                  player.addCustomChatCompletions(List.of("velo"));
                  player.storeCookie(Key.key("extrasv", "k"), new byte[] {4, 2});
                  player.requestCookie(Key.key("extrasv", "k"));
                  player.spoofChatInput("/from velocity");
                }
                case "old" -> {
                  try { player.requestCookie(Key.key("extrasv", "k")); } catch (IllegalArgumentException refused) { signal("old-cookie"); }
                  try { player.setServerLinks(List.of()); } catch (IllegalArgumentException refused) { signal("old-links"); }
                  player.spoofChatInput("hi");
                  signal("old-chat");
                  player.addCustomChatCompletions(List.of("fine"));
                  signal("old-done");
                }
                default -> { }
              }
              signal("done:" + invocation.arguments()[1]);
            } catch (RuntimeException failed) {
              signal("failed:" + failed);
            }
          });
        }
        @Subscribe public void cookie(CookieReceiveEvent event) {
          signal("cookie:" + event.getPlayer().getUsername() + ":" + event.getOriginalKey().asString() + ":" + java.util.Arrays.toString(event.getOriginalData()));
        }
      }
      """;

  private static void velocityPluginsUseAllOfIt() throws Exception {
    Queue<String> signals = new ConcurrentLinkedQueue<>();
    System.getProperties().put("extras.test.signals", signals);
    try {
      Path plugins = LoginFlowTests.compiledPlugin("extrasv.ExtrasV", EXTRASV);
      try (ModernBackend lobby = new ModernBackend(767);
           Proxy proxy = new Proxy(List.of(lobby.server()), AuthenticationSettings.offline(), null, plugins, ForwardingMode.NONE, Optional.empty());
           ModernClient client = ModernClient.open(767, proxy.port(), "VExtra")) {
        require(waitFor(() -> proxy.runtime.plugins().plugin("extrasv").isPresent(), 10_000), "extrasv enabled");
        client.playing(proxy);
        proxy.runtime.commands().execute(proxy.runtime.console(), "extras VExtra all");
        require(waitFor(() -> signals.contains("done:all"), 10_000), "the plugin ran: " + signals);
        byte[] links = client.await(ConnectionState.PLAY, PacketKind.PLAY_SERVER_LINKS, 1).getFirst();
        require(Arrays.equals(links, PlayerApiPackets.serverLinks(0x7B, 767, List.of(ServerLink.of(ServerLink.Type.WEBSITE, URI.create("https://site.example")),
            ServerLink.of(Text.of("Rules"), URI.create("https://rules.example"))))), "Velocity's links, as Conduit's");
        require(Arrays.equals(client.await(ConnectionState.PLAY, PacketKind.PLAY_CHAT_SUGGESTIONS, 1).getFirst(),
            new byte[] {0x18, 0, 1, 4, 'v', 'e', 'l', 'o'}), "the completion added");
        require(waitFor(() -> signals.contains("cookie:VExtra:extrasv:k:[4, 2]"), 10_000), "CookieReceiveEvent with the stored cookie: " + signals);
        int command = lobby.p.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT_COMMAND);
        int answer = lobby.p.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_COOKIE_RESPONSE);
        require(lobby.await(packet -> id(packet) == command && chatLine(packet).equals("from velocity")), "the spoofed command");
        require(lobby.received(packet -> id(packet) == answer).isEmpty(), "and the cookie answer stayed at the proxy");
      }
      try (ModernBackend lobby = new ModernBackend(765);
           Proxy proxy = new Proxy(List.of(lobby.server()), AuthenticationSettings.offline(), null, plugins, ForwardingMode.NONE, Optional.empty());
           ModernClient client = ModernClient.open(765, proxy.port(), "VOld")) {
        require(waitFor(() -> proxy.runtime.plugins().plugin("extrasv").isPresent(), 10_000), "extrasv enabled");
        client.playing(proxy);
        proxy.runtime.commands().execute(proxy.runtime.console(), "extras VOld old");
        require(waitFor(() -> signals.contains("old-done"), 10_000), "the plugin ran: " + signals);
        require(signals.containsAll(List.of("old-cookie", "old-links", "old-chat")), "1.20.4 has no cookies or links, but takes unsigned chat: " + signals);
        int chat = lobby.p.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT);
        require(lobby.await(packet -> id(packet) == chat && chatLine(packet).equals("hi")), "the spoofed chat");
        require(client.await(ConnectionState.PLAY, PacketKind.PLAY_CHAT_SUGGESTIONS, 1).size() == 1, "but it has completions");
      }
    } finally {
      System.getProperties().remove("extras.test.signals");
    }
  }

  // --- harness ------------------------------------------------------------------------------------

  private interface Action { void run() throws Exception; }
  private static void refused(Class<? extends Throwable> type, Action action, String what) throws Exception {
    try {
      action.run();
    } catch (Throwable thrown) {
      if (type.isInstance(thrown)) return;
      throw new AssertionError(what + ": expected " + type.getSimpleName() + ", got " + thrown, thrown);
    }
    throw new AssertionError(what + ": expected " + type.getSimpleName() + ", nothing was thrown");
  }

  /**
   * A 1.20.2+ server at one protocol: logs the player in, configures them at once, sends a Join Game,
   * then keeps every Play packet it is sent and sends what it is told to.
   */
  static final class ModernBackend implements AutoCloseable {
    final ProtocolDefinition p;
    private final ServerSocket listener = new ServerSocket(0);
    private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Socket> playing = new ConcurrentHashMap<>();
    private final List<byte[]> received = Collections.synchronizedList(new ArrayList<>());
    /** Each player's entity id, from 7 up in the order they join. */
    private final java.util.concurrent.atomic.AtomicInteger entities = new java.util.concurrent.atomic.AtomicInteger(7);
    ModernBackend(int protocol) throws IOException {
      p = ProtocolDefinition.forVersion(protocol);
      Thread.ofPlatform().daemon().name("extras-backend-" + protocol).start(() -> {
        try {
          while (true) {
            Socket socket = listener.accept();
            sockets.add(socket);
            Thread.ofPlatform().daemon().start(() -> serve(socket));
          }
        } catch (IOException closed) { }
      });
    }
    BackendServer server() { return new BackendServer("lobby", new InetSocketAddress("127.0.0.1", listener.getLocalPort())); }
    private void serve(Socket socket) {
      try (socket) {
        var in = socket.getInputStream();
        if (Handshake.decode(MinecraftFrames.read(in, 4096)).nextState() == 1) return;
        var start = new DataInputStream(new ByteArrayInputStream(MinecraftFrames.read(in, 4096)));
        MinecraftInput.varInt(start);
        String player = MinecraftInput.string(start, 16);
        UUID uuid = new UUID(start.readLong(), start.readLong());
        int number = p.version().number();
        write(socket, packet(p.id(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_SUCCESS), output -> {
          output.writeLong(uuid.getMostSignificantBits()); output.writeLong(uuid.getLeastSignificantBits());
          MinecraftOutput.string(output, player); MinecraftOutput.varInt(output, 0);
          if (number == 766 || number == 767) output.writeBoolean(false); // strict error handling, 1.20.5-1.21.1 only
        }));
        int loginAck = p.id(ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, PacketKind.LOGIN_ACKNOWLEDGED);
        while (MinecraftFrames.read(in, 1 << 16)[0] != loginAck) { }
        write(socket, new byte[] {(byte) p.id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_FINISH)});
        int finished = p.id(ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_FINISH);
        while (MinecraftFrames.read(in, 1 << 16)[0] != finished) { }
        write(socket, joinGame(p, entities.getAndIncrement()));
        playing.put(player, socket);
        while (true) received.add(MinecraftFrames.read(in, 1 << 20));
      } catch (Exception ended) { }
    }
    /**
     * A 1.20.3+ Join Game in a single overworld; 1.20.5 named the dimension type by id and added the
     * secure-chat flag, 1.21.2 the sea level before it.
     */
    private static byte[] joinGame(ProtocolDefinition p, int entity) throws IOException {
      int number = p.version().number();
      return packet(p.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN), output -> {
        output.writeInt(entity); output.writeBoolean(false);
        MinecraftOutput.varInt(output, 1); MinecraftOutput.string(output, "minecraft:overworld");
        MinecraftOutput.varInt(output, 20); MinecraftOutput.varInt(output, 10); MinecraftOutput.varInt(output, 10);
        output.writeBoolean(false); output.writeBoolean(true); output.writeBoolean(false);
        if (number >= 766) MinecraftOutput.varInt(output, 0); else MinecraftOutput.string(output, "minecraft:overworld");
        MinecraftOutput.string(output, "minecraft:overworld");
        output.writeLong(0L); output.writeByte(0); output.writeByte(-1);
        output.writeBoolean(false); output.writeBoolean(false); output.writeBoolean(false);
        MinecraftOutput.varInt(output, 0);
        if (number >= 768) MinecraftOutput.varInt(output, 63);
        if (number >= 766) output.writeBoolean(false);
      });
    }
    void send(String player, byte[] packet) throws Exception {
      require(waitFor(() -> playing.containsKey(player), 10_000), player + " is playing");
      write(playing.get(player), packet);
    }
    List<byte[]> received(Predicate<byte[]> match) {
      synchronized (received) { return received.stream().filter(match).toList(); }
    }
    boolean await(Predicate<byte[]> match) throws InterruptedException { return waitFor(() -> !received(match).isEmpty(), 10_000); }
    private static void write(Socket socket, byte[] packet) throws IOException {
      synchronized (socket) { MinecraftFrames.write(socket.getOutputStream(), packet); }
    }
    @Override public void close() throws IOException {
      listener.close();
      synchronized (sockets) { for (Socket socket : sockets) socket.close(); }
    }
  }

  /**
   * A 1.20.2+ client at one protocol that keeps up as the real one does, keeps every packet it is sent
   * with the state it arrived in, and keeps cookies and answers requests for them as the real one does.
   */
  static final class ModernClient implements AutoCloseable {
    private record Frame(ConnectionState state, byte[] data) {}
    private final ProtocolDefinition p;
    private final Socket socket;
    private final String name;
    private volatile ConnectionState state = ConnectionState.LOGIN;
    private final List<Frame> received = Collections.synchronizedList(new ArrayList<>());
    final Map<String, byte[]> cookies = new ConcurrentHashMap<>();
    private ModernClient(ProtocolDefinition p, Socket socket, String name) { this.p = p; this.socket = socket; this.name = name; }
    static ModernClient open(int protocol, int port, String name) throws IOException {
      Socket socket = new Socket("127.0.0.1", port);
      socket.setSoTimeout(20_000);
      ModernClient client = new ModernClient(ProtocolDefinition.forVersion(protocol), socket, name);
      MinecraftFrames.write(socket.getOutputStream(), new Handshake(protocol, "localhost", 25565, 2).encode());
      UUID uuid = LoginStart.offlineUuid(name);
      MinecraftFrames.write(socket.getOutputStream(), packet(0, output -> {
        MinecraftOutput.string(output, name); output.writeLong(uuid.getMostSignificantBits()); output.writeLong(uuid.getLeastSignificantBits());
      }));
      Thread.ofPlatform().daemon().name("extras-client-" + name).start(client::read);
      return client;
    }
    private void read() {
      var c2s = PacketDirection.CLIENT_TO_SERVER;
      var s2c = PacketDirection.SERVER_TO_CLIENT;
      try {
        while (true) {
          byte[] frame = MinecraftFrames.read(socket.getInputStream(), 1 << 21);
          ConnectionState at = state;
          received.add(new Frame(at, frame));
          int packetId = id(frame);
          boolean play = at == ConnectionState.PLAY;
          if (at == ConnectionState.LOGIN && p.is(at, s2c, packetId, PacketKind.LOGIN_SUCCESS)) {
            state = ConnectionState.CONFIGURATION;
            send(new byte[] {(byte) p.id(at, c2s, PacketKind.LOGIN_ACKNOWLEDGED)});
          } else if (at == ConnectionState.CONFIGURATION && p.is(at, s2c, packetId, PacketKind.CONFIGURATION_KNOWN_PACKS)) {
            send(new byte[] {(byte) p.id(at, c2s, PacketKind.CONFIGURATION_KNOWN_PACKS), 0});
          } else if (at == ConnectionState.CONFIGURATION && p.is(at, s2c, packetId, PacketKind.CONFIGURATION_FINISH)) {
            state = ConnectionState.PLAY;
            send(new byte[] {(byte) p.id(at, c2s, PacketKind.CONFIGURATION_FINISH)});
          } else if (play && p.is(at, s2c, packetId, PacketKind.PLAY_START_CONFIGURATION)) {
            state = ConnectionState.CONFIGURATION;
            send(new byte[] {(byte) p.id(at, c2s, PacketKind.PLAY_CONFIGURATION_ACKNOWLEDGED)});
          } else if (p.is(at, s2c, packetId, play ? PacketKind.PLAY_STORE_COOKIE : PacketKind.CONFIGURATION_STORE_COOKIE)) {
            var input = new DataInputStream(new ByteArrayInputStream(frame));
            MinecraftInput.varInt(input);
            cookies.put(MinecraftInput.string(input, 32767), MinecraftInput.bytes(input, PlayerApiPackets.COOKIE_MAX_BYTES));
          } else if (p.is(at, s2c, packetId, play ? PacketKind.PLAY_COOKIE_REQUEST : PacketKind.CONFIGURATION_COOKIE_REQUEST)) {
            var input = new DataInputStream(new ByteArrayInputStream(frame));
            MinecraftInput.varInt(input);
            String key = MinecraftInput.string(input, 32767);
            byte[] cookie = cookies.get(key);
            send(packet(p.id(at, c2s, play ? PacketKind.PLAY_COOKIE_RESPONSE : PacketKind.CONFIGURATION_COOKIE_RESPONSE), output -> {
              MinecraftOutput.string(output, key);
              output.writeBoolean(cookie != null);
              if (cookie != null) { MinecraftOutput.varInt(output, cookie.length); output.write(cookie); }
            }));
          }
        }
      } catch (IOException closed) { }
    }
    synchronized void send(byte[] packet) throws IOException { MinecraftFrames.write(socket.getOutputStream(), packet); }
    /** The player once the client is in Play and the proxy has let them in. */
    Player playing(Proxy proxy) throws Exception {
      require(waitFor(() -> state == ConnectionState.PLAY, 15_000), name + " never reached Play, it is in " + state);
      require(waitFor(() -> proxy.runtime.player(name).map(player -> player.connectionState().equals("PLAY")).orElse(false), 10_000), name + " is playing");
      return proxy.runtime.player(name).orElseThrow();
    }
    /** The first {@code count} packets of {@code kind} it was sent in {@code state}. */
    List<byte[]> await(ConnectionState in, PacketKind kind, int count) throws InterruptedException {
      Predicate<Frame> match = frame -> frame.state() == in && p.is(in, PacketDirection.SERVER_TO_CLIENT, id(frame.data()), kind);
      require(waitFor(() -> { synchronized (received) { return received.stream().filter(match).count() >= count; } }, 10_000),
          name + " was sent fewer than " + count + " " + kind);
      synchronized (received) { return received.stream().filter(match).map(Frame::data).limit(count).toList(); }
    }
    @Override public void close() throws IOException { socket.close(); }
  }

  private static String chatLine(byte[] packet) {
    try { return PlayPackets.chatCommand(packet); } catch (IOException unreadable) { return ""; }
  }
  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
