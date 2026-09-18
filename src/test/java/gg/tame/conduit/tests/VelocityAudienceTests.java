// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.NativeApiTests.CHAT_OUT;
import static gg.tame.conduit.tests.NativeApiTests.id;
import static gg.tame.conduit.tests.NativeApiTests.packet;
import static gg.tame.conduit.tests.NativeApiTests.require;
import static gg.tame.conduit.tests.VelocityCompatTests.awaitSignal;

import gg.tame.conduit.api.player.ConnectResult;
import gg.tame.conduit.api.player.ResourcePack;
import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.auth.Authenticators;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.crypto.RsaKeys;
import gg.tame.conduit.network.MinecraftProxy;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.session.ClientResourcePacks;
import gg.tame.conduit.tests.NativeApiTests.Backend;
import gg.tame.conduit.tests.NativeApiTests.Client;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * What two real plugins needed, checked with a compiled one: ServerPostConnectEvent for the first
 * server as well (ForcePack sends its pack from it), the proxy and a server as audiences of their
 * players and a player's Adventure pointers (TitleAnnouncer's "all" and "server:" targets and its
 * boss bar countdown), the config libraries plugins take from the proxy, and a loaded pack offered
 * again staying loaded (ForcePack re-offers until it sees it applied).
 */
public final class VelocityAudienceTests {
  public static void main(String[] arguments) throws Exception { run(); }

  private static final ProtocolDefinition P47 = ProtocolDefinition.forVersion(47);
  private static final int TITLE = P47.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_TITLE);
  private static final int HEADER = P47.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_TAB_LIST_HEADER);

  private static final String VAUDIENCE = """
      package vaudience;
      import com.velocitypowered.api.command.SimpleCommand;
      import com.velocitypowered.api.event.Subscribe;
      import com.velocitypowered.api.event.player.ServerPostConnectEvent;
      import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
      import com.velocitypowered.api.plugin.Plugin;
      import com.velocitypowered.api.proxy.Player;
      import com.velocitypowered.api.proxy.ProxyServer;
      import javax.inject.Inject;
      import net.kyori.adventure.audience.Audience;
      import net.kyori.adventure.audience.ForwardingAudience;
      import net.kyori.adventure.bossbar.BossBar;
      import net.kyori.adventure.identity.Identity;
      import net.kyori.adventure.text.Component;
      import net.kyori.adventure.title.Title;

      @Plugin(id = "vaudience", name = "VAudience", version = "1.0")
      public final class Main {
        @SuppressWarnings("unchecked")
        static void signal(String value) { ((java.util.Queue<String>) System.getProperties().get("velocity.test.signals")).add(value); }
        private final ProxyServer proxy;
        @Inject public Main(ProxyServer proxy) { this.proxy = proxy; }

        @Subscribe public void init(ProxyInitializeEvent event) {
          proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("vaudience").plugin(this).build(), (SimpleCommand) this::run);
        }
        @Subscribe public void connected(ServerPostConnectEvent event) {
          signal("post:" + event.getPlayer().getUsername() + ":"
              + (event.getPreviousServer() == null ? "none" : event.getPreviousServer().getServerInfo().getName()) + ":"
              + event.getPlayer().getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse("nowhere"));
        }
        /** The players an audience forwards to, by name. */
        private static String members(Audience audience) {
          if (!(audience instanceof ForwardingAudience group)) return "not-forwarding";
          java.util.List<String> names = new java.util.ArrayList<>();
          for (Audience member : group.audiences()) names.add(((Player) member).getUsername());
          java.util.Collections.sort(names);
          return String.join(",", names);
        }
        private void run(SimpleCommand.Invocation invocation) {
          String[] arguments = invocation.arguments();
          try {
            switch (arguments[0]) {
              case "all" -> {
                proxy.showTitle(Title.title(Component.text("t-all"), Component.text("s-all")));
                proxy.sendActionBar(Component.text("a-all"));
                proxy.sendPlayerListHeaderAndFooter(Component.text("h-all"), Component.text("f-all"));
                // 1.8 has no boss bar: nothing to see, and nothing to refuse either.
                proxy.showBossBar(BossBar.bossBar(Component.text("b-all"), 1, BossBar.Color.RED, BossBar.Overlay.PROGRESS));
                proxy.sendMessage(Component.text("c-all"));
                signal("all:" + members(proxy));
              }
              case "server" -> {
                Audience server = proxy.getServer(arguments[1]).orElseThrow();
                server.showTitle(Title.title(Component.text("t-" + arguments[1]), Component.empty()));
                signal("server:" + arguments[1] + ":" + members(server));
              }
              case "pointers" -> {
                for (Player player : proxy.getAllPlayers()) {
                  signal("pointers:" + player.getUsername() + ":" + player.get(Identity.UUID).map(player.getUniqueId()::equals).orElse(false)
                      + ":" + player.get(Identity.NAME).orElse("none"));
                }
              }
              case "libraries" -> {
                int hocon = org.spongepowered.configurate.hocon.HoconConfigurationLoader.builder()
                    .buildAndLoadString("a { b = 3 }").node("a", "b").getInt();
                Object toml = new com.electronwill.nightconfig.toml.TomlParser().parse("x = 5").get("x");
                signal("libraries:" + hocon + ":" + toml);
              }
              default -> signal("unknown:" + arguments[0]);
            }
          } catch (Exception failed) {
            signal("failed:" + arguments[0] + ":" + failed);
          }
        }
      }
      """;

  public static void run() throws Exception {
    aLoadedPackOfferedAgainStaysLoaded();
    aPluginAnnouncesThroughTheProxyAndItsServers();
    System.out.println("VelocityAudienceTests OK");
  }

  private static void aPluginAnnouncesThroughTheProxyAndItsServers() throws Exception {
    VelocityCompatTests.installSignals();
    Path root = TempFiles.dir("velocity-audience");
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    VelocityCompatTests.jar(VelocityCompatTests.compile(root, "vaudience.Main", VAUDIENCE, List.of(), true), plugins.resolve("vaudience.jar"), null);
    try (Backend lobby = new Backend("lobby"); Backend survival = new Backend("survival")) {
      ConduitConfiguration configuration = VelocityCompatTests.configuration(List.of(lobby.server(), survival.server()));
      MinecraftProxy proxy = new MinecraftProxy(configuration, Authenticators.create(configuration.authentication()), RsaKeys.generate(), plugins);
      Thread serving = Thread.ofPlatform().daemon().name("velocity-audience-serve").start(() -> {
        try { proxy.serve(); } catch (IOException ignored) { }
      });
      try (Client ann = Client.join(proxy.port(), "Ann"); Client bob = Client.join(proxy.port(), "Bob")) {
        // The first server raises it too, with no previous server, as on Velocity.
        awaitSignal("post:Ann:none:lobby");
        awaitSignal("post:Bob:none:lobby");
        var bobPlayer = proxy.runtime().player("Bob").orElseThrow();
        var moved = bobPlayer.connectWithResult(proxy.runtime().servers().getServer("survival").orElseThrow()).get(10, TimeUnit.SECONDS);
        require(moved.status() == ConnectResult.Status.CONNECTED, "Bob moved, got " + moved);
        awaitSignal("post:Bob:lobby:survival");

        // The proxy forwards to every player: each gets the title, action bar, header and chat.
        command(proxy, "vaudience all");
        awaitSignal("all:Ann,Bob");
        for (Client client : List.of(ann, bob)) {
          require(client.await(packet -> id(packet) == TITLE && has(packet, "t-all"))
              && client.await(packet -> id(packet) == TITLE && has(packet, "s-all")), "the proxy's title and subtitle");
          require(client.await(packet -> id(packet) == HEADER && has(packet, "h-all") && has(packet, "f-all")), "its header and footer");
          require(client.await(packet -> id(packet) == CHAT_OUT && has(packet, "a-all") && packet[packet.length - 1] == 2),
              "its action bar, as 1.8 shows one: a chat line in position 2");
          require(client.await(packet -> id(packet) == CHAT_OUT && has(packet, "c-all")), "and its chat");
        }

        // A server forwards to the players on it and nobody else.
        command(proxy, "vaudience server survival");
        awaitSignal("server:survival:Bob");
        command(proxy, "vaudience server lobby");
        awaitSignal("server:lobby:Ann");
        require(bob.await(packet -> has(packet, "t-survival")) && ann.await(packet -> has(packet, "t-lobby")), "each server's title reached its player");
        bobPlayer.sendMessage("barrier");
        require(bob.await(packet -> has(packet, "barrier")), "a message after the titles");
        require(bob.received(packet -> has(packet, "t-lobby")).isEmpty() && ann.received(packet -> has(packet, "t-survival")).isEmpty(),
            "neither got the other server's title");

        // A player answers Adventure's pointers, which plugins use to find the player behind an audience.
        command(proxy, "vaudience pointers");
        awaitSignal("pointers:Ann:true:Ann");
        awaitSignal("pointers:Bob:true:Bob");

        // Configurate (velocity-api's POM) and night-config's TOML (Velocity's proxy) are on the class path.
        command(proxy, "vaudience libraries");
        awaitSignal("libraries:3:5");
      } finally {
        proxy.close();
        serving.join(10_000);
      }
    }
  }

  private static void command(MinecraftProxy proxy, String line) {
    require(proxy.runtime().commands().execute(proxy.runtime().console(), line), "ran " + line);
  }
  private static boolean has(byte[] packet, String text) { return new String(packet, StandardCharsets.UTF_8).contains(text); }

  /** A 1.12.2 client that loaded a pack is offered it again, as a plugin waiting to see it applied does. */
  private static void aLoadedPackOfferedAgainStaysLoaded() throws Exception {
    ProtocolDefinition v340 = ProtocolDefinition.forVersion(340);
    List<byte[]> written = new CopyOnWriteArrayList<>();
    ClientResourcePacks packs = new ClientResourcePacks(null, v340, written::add, event -> { });
    packs.afterWrite(ConnectionState.PLAY, packet(v340.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN),
        out -> out.writeInt(1)));
    ResourcePack pack = new ResourcePack(new UUID(1, 2), "https://example.invalid/pack.zip",
        "0123456789abcdef0123456789abcdef01234567", true, Text.empty());
    // 1.12.2's Resource Pack Status: the result alone.
    byte[] accepted = packet(0x18, out -> MinecraftOutput.varInt(out, 3));
    byte[] loaded = packet(0x18, out -> MinecraftOutput.varInt(out, 0));
    byte[] declined = packet(0x18, out -> MinecraftOutput.varInt(out, 1));
    ConnectionState play = ConnectionState.PLAY;
    require(packs.offer(pack) && packs.fromClient(play, accepted) && packs.fromClient(play, loaded) && packs.offered().getFirst().loaded(), "loaded");
    require(packs.offer(pack) && written.size() == 2 && packs.offered().getFirst().loaded(), "offered again: written again, still loaded");
    require(packs.fromClient(play, accepted) && packs.offered().getFirst().loaded(), "while the client accepts it again");
    require(packs.fromClient(play, loaded) && packs.offered().getFirst().loaded(), "and once it has loaded it again");
    ResourcePack moved = new ResourcePack(pack.id(), pack.url() + "?v=2", pack.hash(), true, Text.empty());
    require(packs.offer(moved) && !packs.offered().getFirst().loaded(), "the same id at another URL is a new pack, pending");
    require(packs.fromClient(play, declined) && packs.offered().isEmpty(), "and declined, it is gone");
  }
}
