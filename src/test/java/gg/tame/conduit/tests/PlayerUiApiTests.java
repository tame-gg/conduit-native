// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.NativeApiTests.waitFor;

import gg.tame.conduit.config.AuthenticationSettings;
import gg.tame.conduit.config.ForwardingMode;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.session.PlayerSession;
import gg.tame.conduit.tests.LoginFlowTests.Proxy;
import gg.tame.conduit.tests.PlayerExtrasTests.ModernBackend;
import gg.tame.conduit.tests.PlayerExtrasTests.ModernClient;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.net.ConnectException;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Velocity API that used to throw: getProtocolState, setGameProfileProperties, playSound with another
 * player as the emitter, closeDialog, createRawRegisteredServer and closeListeners, and openBook,
 * which still throws. One compiled plugin, a real proxy, scripted 1.20.4 and 26.2 clients.
 */
public final class PlayerUiApiTests {
  public static void main(String[] arguments) throws Exception { run(); }

  public static void run() throws Exception {
    Queue<String> signals = new ConcurrentLinkedQueue<>();
    System.getProperties().put("ui.test.signals", signals);
    try {
      Path plugins = LoginFlowTests.compiledPlugin("ui.Main", PLUGIN);
      twoPlayersOn765(plugins, signals);
      aDialogIsClosedOn776(plugins, signals);
    } finally {
      System.getProperties().remove("ui.test.signals");
    }
    System.out.println("PlayerUiApiTests OK");
  }

  private static final String PLUGIN = """
      package ui;
      import com.velocitypowered.api.command.SimpleCommand;
      import com.velocitypowered.api.event.Subscribe;
      import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
      import com.velocitypowered.api.plugin.Plugin;
      import com.velocitypowered.api.proxy.Player;
      import com.velocitypowered.api.proxy.ProxyServer;
      import com.velocitypowered.api.proxy.server.RegisteredServer;
      import com.velocitypowered.api.proxy.server.ServerInfo;
      import com.velocitypowered.api.util.GameProfile;
      import java.util.List;
      import javax.inject.Inject;
      import net.kyori.adventure.inventory.Book;
      import net.kyori.adventure.key.Key;
      import net.kyori.adventure.sound.Sound;
      import net.kyori.adventure.text.Component;

      @Plugin(id = "ui", name = "ui", version = "1.0")
      public final class Main {
        @SuppressWarnings("unchecked")
        static void signal(String value) { ((java.util.Queue<String>) System.getProperties().get("ui.test.signals")).add(value); }
        static final Sound SOUND = Sound.sound(Key.key("entity.experience_orb.pickup"), Sound.Source.PLAYER, 1f, 1f);
        private final ProxyServer proxy;
        @Inject public Main(ProxyServer proxy) { this.proxy = proxy; }

        @Subscribe public void init(ProxyInitializeEvent event) {
          proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("ui").plugin(this).build(), (SimpleCommand) invocation -> {
            String[] args = invocation.arguments();
            try {
              switch (args[0]) {
                case "state" -> signal("state:" + player(args[1]).getProtocolState());
                case "emit" -> { player(args[1]).playSound(SOUND, player(args[2])); signal("emitted"); }
                case "props" -> {
                  Player player = player(args[1]);
                  player.setGameProfileProperties(List.of(new GameProfile.Property("textures", "skin", "sig")));
                  GameProfile.Property got = player.getGameProfile().getProperties().get(0);
                  signal("props:" + player.getGameProfileProperties().size() + ":" + got.getName() + ":" + got.getValue() + ":" + got.getSignature());
                }
                case "dialog" -> { player(args[1]).closeDialog(); signal("dialog-closed"); }
                case "book" -> player(args[1]).openBook(Book.book(Component.text("t"), Component.text("a"), Component.text("p")));
                case "raw" -> {
                  ServerInfo info = new ServerInfo("lobby", proxy.getBoundAddress());
                  RegisteredServer raw = proxy.createRawRegisteredServer(info);
                  signal("raw:" + raw.getServerInfo().equals(info) + ":" + proxy.getAllServers().size() + ":" + raw.getPlayersConnected().size());
                  signal("rawping:" + raw.ping().join().getVersion().getProtocol());
                  signal("rawconnect:" + player(args[1]).createConnectionRequest(raw).connect().join().getStatus());
                }
                case "close" -> { proxy.closeListeners(); signal("closed"); }
                default -> { }
              }
            } catch (RuntimeException failed) {
              signal(args[0] + "-threw:" + failed.getClass().getSimpleName() + ":" + failed.getMessage());
            }
          });
        }
        private Player player(String name) { return proxy.getPlayer(name).orElseThrow(); }
      }
      """;

  private static void twoPlayersOn765(Path plugins, Queue<String> signals) throws Exception {
    signals.clear();
    try (ModernBackend lobby = new ModernBackend(765);
         Proxy proxy = new Proxy(List.of(lobby.server()), AuthenticationSettings.offline(), null, plugins, ForwardingMode.NONE, Optional.empty());
         ModernClient ann = ModernClient.open(765, proxy.port(), "Ann")) {
      require(waitFor(() -> proxy.runtime.plugins().plugin("ui").isPresent(), 10_000), "ui enabled");
      ann.playing(proxy);
      try (ModernClient bob = ModernClient.open(765, proxy.port(), "Bob")) {
        bob.playing(proxy);
        int port = proxy.port();

        command(proxy, signals, "state Ann", "state:PLAY");

        // Ann joined first and is entity 7 on the backend, Bob entity 8.
        command(proxy, signals, "emit Ann Bob", "emitted");
        command(proxy, signals, "emit Ann Ann", "emitted");
        List<byte[]> sounds = ann.await(ConnectionState.PLAY, PacketKind.PLAY_ENTITY_SOUND_EFFECT, 2);
        require(entityOf(sounds.get(0)) == 8, "Bob's sound follows Bob's entity, got " + entityOf(sounds.get(0)));
        require(entityOf(sounds.get(1)) == 7, "Ann's own sound follows Ann, got " + entityOf(sounds.get(1)));

        command(proxy, signals, "props Ann", "props:1:textures:skin:sig");
        var profile = ((PlayerSession) proxy.runtime.player("Ann").orElseThrow()).profile();
        require(profile.properties().size() == 1 && profile.properties().get(0).signature().orElse("").equals("sig"),
            "the profile every later backend connection forwards has the new properties: " + profile.properties());

        command(proxy, signals, "dialog Ann", "dialog-closed");
        command(proxy, signals, "book Ann", "book-threw:UnsupportedOperationException:Player.openBook is not supported by Conduit's Velocity compatibility layer");

        command(proxy, signals, "raw Ann", "raw:true:1:0");
        require(waitFor(() -> signals.stream().anyMatch(signal -> signal.startsWith("rawping:")), 10_000), "the raw server answers a ping: " + signals);
        require(waitFor(() -> signals.contains("rawconnect:SERVER_DISCONNECTED"), 10_000),
            "nobody is sent to a raw server, though it shares the name of the one Ann is on: " + signals);

        command(proxy, signals, "close", "closed");
        boolean refused = false;
        try (Socket late = new Socket("127.0.0.1", port)) { } catch (ConnectException expected) { refused = true; }
        require(refused, "nobody new gets in once the listeners are closed");
        signals.clear();
        command(proxy, signals, "emit Ann Bob", "emitted");
        require(ann.await(ConnectionState.PLAY, PacketKind.PLAY_ENTITY_SOUND_EFFECT, 3).size() == 3, "Ann is still connected and still hears");
      }
    }
  }

  private static void aDialogIsClosedOn776(Path plugins, Queue<String> signals) throws Exception {
    signals.clear();
    try (ModernBackend lobby = new ModernBackend(776);
         Proxy proxy = new Proxy(List.of(lobby.server()), AuthenticationSettings.offline(), null, plugins, ForwardingMode.NONE, Optional.empty());
         ModernClient client = ModernClient.open(776, proxy.port(), "Dia")) {
      require(waitFor(() -> proxy.runtime.plugins().plugin("ui").isPresent(), 10_000), "ui enabled");
      client.playing(proxy);
      command(proxy, signals, "dialog Dia", "dialog-closed");
      byte[] clear = client.await(ConnectionState.PLAY, PacketKind.PLAY_CLEAR_DIALOG, 1).get(0);
      require(Arrays.equals(clear, new byte[] {(byte) 0x8B, 0x01}), "26.2's Clear Dialog, id 139 and nothing else: " + Arrays.toString(clear));
    }
  }

  private static void command(Proxy proxy, Queue<String> signals, String line, String expected) throws Exception {
    proxy.runtime.commands().execute(proxy.runtime.console(), "ui " + line);
    require(waitFor(() -> signals.contains(expected), 10_000), "/ui " + line + " signalled " + expected + ": " + signals);
  }

  /** The entity of a 1.19.3+ Entity Sound Effect with its sound inline. */
  private static int entityOf(byte[] packet) throws Exception {
    DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet));
    MinecraftInput.varInt(input);
    require(MinecraftInput.varInt(input) == 0, "the sound is inline");
    MinecraftInput.string(input, 32767);
    input.readBoolean();
    MinecraftInput.varInt(input);
    return MinecraftInput.varInt(input);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
