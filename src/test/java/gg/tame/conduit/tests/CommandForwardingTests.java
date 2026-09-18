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
    System.out.println("CommandForwardingTests OK");
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
