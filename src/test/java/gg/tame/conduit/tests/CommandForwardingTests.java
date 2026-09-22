// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.NativeApiTests.CHAT_IN;
import static gg.tame.conduit.tests.NativeApiTests.CHAT_OUT;
import static gg.tame.conduit.tests.NativeApiTests.chat;
import static gg.tame.conduit.tests.NativeApiTests.chatText;
import static gg.tame.conduit.tests.NativeApiTests.id;
import static gg.tame.conduit.tests.NativeApiTests.require;
import static gg.tame.conduit.tests.NativeApiTests.text;
import static gg.tame.conduit.tests.NativeApiTests.waitFor;

import gg.tame.conduit.api.command.CommandManager;
import gg.tame.conduit.api.event.player.PlayerServerConnectedEvent;
import gg.tame.conduit.config.AuthenticationSettings;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.tests.NativeApiTests.Backend;
import gg.tame.conduit.tests.NativeApiTests.Client;
import gg.tame.conduit.tests.NativeApiTests.Fixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A proxy command that is not there for a player -- a native command whose requirement says no, a
 * Velocity command whose hasPermission does -- leaves the player's command to their backend, as
 * Velocity does, instead of answering it with "You do not have permission".
 */
public final class CommandForwardingTests {
  public static void main(String[] arguments) throws Exception { run(); }

  public static void run() throws Exception {
    aNativeCommandThatIsNotThereGoesToTheBackend();
    aVelocityCommandTheSourceMayNotUseGoesToTheBackend();
    listenersRewriteAndForwardCommands();
    aModernClientsChatReachesListeners();
    System.out.println("CommandForwardingTests OK");
  }

  /**
   * A 1.20.4 client's chat raises PlayerChatEvent. A withheld line reaches the backend only as the
   * acknowledgement count it carried; a rewrite is carried out for an unsigned line and not for a
   * signed one; a line nobody touched arrives byte for byte.
   */
  private static void aModernClientsChatReachesListeners() throws Exception {
    gg.tame.conduit.protocol.ProtocolDefinition p = gg.tame.conduit.protocol.ProtocolDefinition.forVersion(765);
    var play = gg.tame.conduit.protocol.ConnectionState.PLAY;
    var config = gg.tame.conduit.protocol.ConnectionState.CONFIGURATION;
    var in = gg.tame.conduit.protocol.PacketDirection.CLIENT_TO_SERVER;
    var out = gg.tame.conduit.protocol.PacketDirection.SERVER_TO_CLIENT;
    int configOut = p.id(config, out, gg.tame.conduit.protocol.PacketKind.CONFIGURATION_PLUGIN_MESSAGE);
    byte finishOut = (byte) p.id(config, out, gg.tame.conduit.protocol.PacketKind.CONFIGURATION_FINISH);
    byte finishIn = (byte) p.id(config, in, gg.tame.conduit.protocol.PacketKind.CONFIGURATION_FINISH);
    int chatId = p.id(play, in, gg.tame.conduit.protocol.PacketKind.PLAY_CHAT);
    int ackId = p.id(play, in, gg.tame.conduit.protocol.PacketKind.PLAY_CHAT_ACKNOWLEDGEMENT);
    try (java.net.ServerSocket listener = new java.net.ServerSocket(0)) {
      ModLoaderTests.Mock backend = new ModLoaderTests.Mock(listener, "chat", new byte[0], configOut, finishOut, finishIn);
      DisplayApiTests.platform("chat-backend", backend);
      gg.tame.conduit.config.ConduitConfiguration configuration = new gg.tame.conduit.config.ConduitConfiguration(
          new java.net.InetSocketAddress("127.0.0.1", ModLoaderTests.reservePort()), 8192,
          gg.tame.conduit.config.ForwardingMode.NONE, java.util.Optional.empty(),
          List.of(new gg.tame.conduit.config.BackendServer("lobby", new java.net.InetSocketAddress("127.0.0.1", listener.getLocalPort()))),
          List.of("lobby"), List.of());
      try (gg.tame.conduit.network.MinecraftProxy proxy = new gg.tame.conduit.network.MinecraftProxy(configuration)) {
        NativeApiTests.Recorder recorder = new NativeApiTests.Recorder();
        proxy.runtime().events().register(new NativeApiTests.TestPlugin("chat"), recorder);
        recorder.hook = event -> {
          if (!(event instanceof gg.tame.conduit.api.event.player.PlayerChatEvent chat)) return;
          if (chat.message().startsWith("drop")) chat.setCancelled(true);
          if (chat.message().startsWith("rewrite")) chat.setMessage("rewritten");
        };
        DisplayApiTests.platform("chat-serve", () -> { try { proxy.serve(); } catch (Exception ignored) { } });
        try (java.net.Socket client = new java.net.Socket("127.0.0.1", proxy.port())) {
          client.setSoTimeout(15_000);
          java.io.InputStream input = client.getInputStream();
          java.io.OutputStream output = client.getOutputStream();
          MinecraftFrames.write(output, new gg.tame.conduit.protocol.Handshake(765, "localhost", 25565, 2).encode());
          MinecraftFrames.write(output, ModLoaderTests.loginStart());
          require(MinecraftFrames.read(input, 8192)[0] == 0x02, "Login Success");
          MinecraftFrames.write(output, new byte[] {0x03});
          ModLoaderTests.readConfiguration(input, finishOut);
          MinecraftFrames.write(output, new byte[] {finishIn});
          while (MinecraftFrames.read(input, 1 << 20)[0] != 0x29) { }
          byte[] kept = modernChat(chatId, "kept", false, 1);
          MinecraftFrames.write(output, kept);
          MinecraftFrames.write(output, modernChat(chatId, "drop acknowledging", true, 2));
          MinecraftFrames.write(output, modernChat(chatId, "drop silently", false, 0));
          MinecraftFrames.write(output, modernChat(chatId, "rewrite unsigned", false, 3));
          byte[] signed = modernChat(chatId, "rewrite signed", true, 4);
          MinecraftFrames.write(output, signed);
          byte[] end = modernChat(chatId, "end", false, 0);
          MinecraftFrames.write(output, end);
          require(waitFor(() -> backend.received.stream().anyMatch(packet -> java.util.Arrays.equals(packet, end)), 10_000),
              "the last line reached the backend");
          List<byte[]> seen;
          synchronized (backend.received) { seen = List.copyOf(backend.received); }
          List<byte[]> chats = seen.stream().filter(packet -> packet[0] == (byte) chatId || packet[0] == (byte) ackId).toList();
          require(chats.size() == 5, "kept, one acknowledgement, the rewrite, the signed line and the end: " + chats.size());
          require(java.util.Arrays.equals(chats.get(0), kept), "an untouched line arrives byte for byte");
          require(java.util.Arrays.equals(chats.get(1), new byte[] {(byte) ackId, 2}), "a withheld line leaves only its acknowledgement count");
          require(java.util.Arrays.equals(chats.get(2), modernChat(chatId, "rewritten", false, 3)), "an unsigned line is rewritten");
          require(java.util.Arrays.equals(chats.get(3), signed), "a signed line is not");
          require(recorder.of(gg.tame.conduit.api.event.player.PlayerChatEvent.class).size() == 6, "every line was put to listeners");
        }
      }
    }
  }

  /** A 1.19.3+ serverbound chat line, signed with a dummy signature when asked. */
  private static byte[] modernChat(int id, String message, boolean signed, int acknowledged) throws Exception {
    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
    try (java.io.DataOutputStream output = new java.io.DataOutputStream(bytes)) {
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, id);
      gg.tame.conduit.protocol.MinecraftOutput.string(output, message);
      output.writeLong(1_700_000_000_000L);
      output.writeLong(42L);
      output.writeBoolean(signed);
      if (signed) output.write(new byte[256]);
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, acknowledged);
      output.write(new byte[] {1, 2, 3});
    }
    return bytes.toByteArray();
  }

  private static void aNativeCommandThatIsNotThereGoesToTheBackend() throws Exception {
    try (Backend lobby = new Backend("lobby"); Fixture proxy = new Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"))) {
      AtomicInteger ran = new AtomicInteger();
      proxy.runtime.commands().register(new NativeApiTests.TestPlugin("secrets"), CommandManager.Command.builder("secret")
          .requires((source, arguments) -> source.username().equals("Admin"))
          .handler((source, arguments) -> ran.incrementAndGet())
          .build());
      try (Client guest = Client.join(proxy.port(), "Guest"); Client admin = Client.join(proxy.port(), "Admin")) {
        require(proxy.recorder.await(PlayerServerConnectedEvent.class, 2), "both joined");
        guest.send(chat("/secret stash"));
        require(lobby.await(packet -> id(packet) == CHAT_IN && chatText(packet).equals("/secret stash")), "the guest's command reached the backend");
        admin.send(chat("/secret stash"));
        require(waitFor(() -> ran.get() == 1, 5_000), "the admin's ran on the proxy");
        Thread.sleep(200);
        require(lobby.received(packet -> id(packet) == CHAT_IN && chatText(packet).equals("/secret stash")).size() == 1,
            "and did not reach the backend too");
        require(guest.received(packet -> id(packet) == CHAT_OUT && text(packet).contains("permission")).isEmpty(), "the guest was not told off");
        var guestPlayer = proxy.runtime.player("Guest").orElseThrow();
        require(!proxy.runtime.commands().execute(guestPlayer, "secret"), "for the guest, the proxy has no such command");
        require(ran.get() == 1, "and it did not run");
      }
    }
  }

  private static final String VSECRET = """
      package vsecret;
      import com.velocitypowered.api.command.SimpleCommand;
      import com.velocitypowered.api.event.Subscribe;
      import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
      import com.velocitypowered.api.plugin.Plugin;
      import com.velocitypowered.api.proxy.Player;
      import com.velocitypowered.api.proxy.ProxyServer;
      import javax.inject.Inject;
      @Plugin(id = "vsecret", name = "vsecret", version = "1.0")
      public final class Main {
        @SuppressWarnings("unchecked")
        static void signal(String value) { ((java.util.Queue<String>) System.getProperties().get("velocity.test.signals")).add(value); }
        private final ProxyServer proxy;
        @Inject public Main(ProxyServer proxy) { this.proxy = proxy; }
        @Subscribe public void chat(com.velocitypowered.api.event.player.PlayerChatEvent event) {
          if (event.getMessage().equals("vhi")) event.setResult(com.velocitypowered.api.event.player.PlayerChatEvent.ChatResult.message("velocity hello"));
        }
        @Subscribe public void rewrite(com.velocitypowered.api.event.command.CommandExecuteEvent event) {
          if (event.getCommand().equals("vshort")) event.setResult(com.velocitypowered.api.event.command.CommandExecuteEvent.CommandResult.command("vsecret x y"));
          if (event.getCommand().equals("vfwd")) event.setResult(com.velocitypowered.api.event.command.CommandExecuteEvent.CommandResult.forwardToServer("vsecret z"));
        }
        @Subscribe public void init(ProxyInitializeEvent event) {
          proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("vsecret").plugin(this).build(), new SimpleCommand() {
            @Override public void execute(Invocation invocation) { signal("vsecret-ran:" + String.join(",", invocation.arguments())); }
            @Override public boolean hasPermission(Invocation invocation) {
              return invocation.source() instanceof Player player && player.getUsername().equals("Admin");
            }
          });
          proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("vtext").plugin(this).build(),
              (com.velocitypowered.api.command.RawCommand) invocation -> signal("vtext:[" + invocation.arguments() + "]"));
          signal("vsecret-ready");
        }
      }
      """;

  private static void aVelocityCommandTheSourceMayNotUseGoesToTheBackend() throws Exception {
    VelocityCompatTests.installSignals();
    Path root = TempFiles.dir("conduit-command-forwarding");
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    VelocityCompatTests.jar(VelocityCompatTests.compile(root, "vsecret.Main", VSECRET, List.of(), true), plugins.resolve("vsecret.jar"), null);
    try (Backend lobby = new Backend("lobby");
         Fixture proxy = new Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"), plugins, AuthenticationSettings.offline(), null)) {
      VelocityCompatTests.awaitSignal("vsecret-ready");
      try (Client guest = Client.join(proxy.port(), "Guest"); Client admin = Client.join(proxy.port(), "Admin")) {
        require(proxy.recorder.await(PlayerServerConnectedEvent.class, 2), "both joined");
        guest.send(chat("/vsecret a b"));
        require(lobby.await(packet -> id(packet) == CHAT_IN && chatText(packet).equals("/vsecret a b")),
            "the command the guest may not use went to the backend, as on Velocity");
        admin.send(chat("/vsecret a b"));
        VelocityCompatTests.awaitSignal("vsecret-ran:a,b");
        Thread.sleep(200);
        require(lobby.received(packet -> id(packet) == CHAT_IN && chatText(packet).equals("/vsecret a b")).size() == 1,
            "the admin's ran on the proxy only");
        require(guest.received(packet -> id(packet) == CHAT_OUT && text(packet).contains("permission")).isEmpty(), "the guest was not told off");

        admin.send(chat("/vshort"));
        VelocityCompatTests.awaitSignal("vsecret-ran:x,y");
        admin.send(chat("/vfwd"));
        require(lobby.await(packet -> id(packet) == CHAT_IN && chatText(packet).equals("/vsecret z")),
            "CommandResult.forwardToServer(command) sends the rewritten command to the backend");
        // A RawCommand is owed its arguments as typed; the argument list collapsed runs of spaces.
        guest.send(chat("/vtext  two   spaced words "));
        VelocityCompatTests.awaitSignal("vtext:[ two   spaced words ]");
        guest.send(chat("vhi"));
        require(lobby.await(packet -> id(packet) == CHAT_IN && chatText(packet).equals("velocity hello")), "ChatResult.message rewrites chat");
        require(lobby.received(packet -> id(packet) == CHAT_IN && chatText(packet).equals("vhi")).isEmpty(), "and the typed line never goes");
      }
    }
  }

  /**
   * A listener's rewrite and forward were ignored with a warning: the command the player typed ran
   * unchanged. A rewrite is now what the proxy looks up, and what the backend gets from a pre-1.19
   * client, and a forward sends the command to the backend even when the proxy has one by that name.
   */
  private static void listenersRewriteAndForwardCommands() throws Exception {
    try (Backend lobby = new Backend("lobby"); Fixture proxy = new Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"))) {
      List<List<String>> ran = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
      proxy.runtime.commands().register(new NativeApiTests.TestPlugin("secrets"), CommandManager.Command.builder("secret")
          .handler((source, arguments) -> ran.add(arguments)).build());
      proxy.recorder.hook = event -> {
        if (event instanceof gg.tame.conduit.api.event.player.PlayerChatEvent chat && chat.message().equals("hi")) chat.setMessage("hello there");
        if (!(event instanceof gg.tame.conduit.api.event.command.CommandExecuteEvent command)) return;
        if (command.command().startsWith("short ")) command.setCommand("/secret " + command.command().substring(6));
        if (command.command().equals("secret loud")) command.forwardToServer();
        if (command.command().equals("hello")) command.setCommand("greet there");
      };
      try (Client client = Client.join(proxy.port(), "Rewriter")) {
        require(proxy.recorder.await(PlayerServerConnectedEvent.class, 1), "joined");
        client.send(chat("/short a b"));
        require(waitFor(() -> ran.contains(List.of("a", "b")), 5_000), "the rewritten command ran on the proxy, got " + ran);
        client.send(chat("/secret loud"));
        require(lobby.await(packet -> id(packet) == CHAT_IN && chatText(packet).equals("/secret loud")), "a forwarded command reaches the backend");
        client.send(chat("/hello"));
        require(lobby.await(packet -> id(packet) == CHAT_IN && chatText(packet).equals("/greet there")), "a rewrite reaches the backend as rewritten");
        require(lobby.received(packet -> id(packet) == CHAT_IN && chatText(packet).equals("/hello")).isEmpty(), "and not as typed");
        require(ran.size() == 1, "the forwarded command did not also run on the proxy, got " + ran);
        var event = proxy.recorder.of(gg.tame.conduit.api.event.command.CommandExecuteEvent.class).getLast();
        require(event.originalCommand().equals("hello") && event.command().equals("greet there"), "the event keeps what was typed");
        client.send(chat("hi"));
        require(lobby.await(packet -> id(packet) == CHAT_IN && chatText(packet).equals("hello there")), "a chat rewrite reaches the backend");
        require(lobby.received(packet -> id(packet) == CHAT_IN && chatText(packet).equals("hi")).isEmpty(), "and the typed line does not");
        try {
          new gg.tame.conduit.api.event.player.PlayerChatEvent(proxy.runtime.player("Rewriter").orElseThrow(), "x").setMessage("/op Rewriter");
          throw new AssertionError("a chat rewrite into a command was accepted");
        } catch (IllegalArgumentException refused) { }
      }
    }
  }
}
