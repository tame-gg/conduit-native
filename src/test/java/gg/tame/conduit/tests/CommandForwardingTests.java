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
      }
    }
  }
}
