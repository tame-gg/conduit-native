// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.auth.Authenticators;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.crypto.RsaKeys;
import gg.tame.conduit.network.MinecraftProxy;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * Adventure's titles, action bar, boss bars, player-list header and Velocity's tab-list entries through
 * the Velocity adapter, end to end: a plugin compiled against the real velocity-api drives them for scripted 1.12.2 clients on
 * two scripted 1.12.2 backends, which is the oldest release with every one of them. What reaches the
 * clients is checked packet by packet, and the adapter's hold on the plugin's bar is checked after a
 * hide and after a disconnect.
 */
public final class VelocityDisplayTests {
  public static void main(String[] a) throws Exception { run(); }

  private static final int TITLE = 0x48;
  private static final int BOSS = 0x0C;
  private static final int HEADER = 0x4A;
  private static final int JOIN_GAME = 0x23;
  private static final int PLAYER_INFO = 0x2E;

  private static final String PLUGIN = """
      package vdisplay;
      import com.velocitypowered.api.command.SimpleCommand;
      import com.velocitypowered.api.event.Subscribe;
      import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
      import com.velocitypowered.api.plugin.Plugin;
      import com.velocitypowered.api.proxy.Player;
      import com.velocitypowered.api.proxy.ProxyServer;
      import com.velocitypowered.api.proxy.player.TabList;
      import com.velocitypowered.api.proxy.player.TabListEntry;
      import com.velocitypowered.api.util.GameProfile;
      import java.time.Duration;
      import java.util.List;
      import java.util.UUID;
      import javax.inject.Inject;
      import net.kyori.adventure.bossbar.BossBar;
      import net.kyori.adventure.text.Component;
      import net.kyori.adventure.text.format.NamedTextColor;
      import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
      import net.kyori.adventure.title.Title;
      import net.kyori.adventure.title.TitlePart;

      @Plugin(id = "vdisplay", name = "VDisplay", version = "1.0")
      public final class VDisplay {
      """ + VelocityCompatTests.SIGNAL_METHOD + """
        static final UUID FAKE = new UUID(0x1234, 0x5678);
        static final BossBar BAR = BossBar.bossBar(Component.text("Raid"), 0.5f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
        /** Bob, kept past his disconnect as a careless plugin keeps a player. */
        static Player stale;
        private final ProxyServer proxy;
        @Inject public VDisplay(ProxyServer proxy) { this.proxy = proxy; }

        @Subscribe
        public void init(ProxyInitializeEvent event) {
          var commands = proxy.getCommandManager();
          commands.register(commands.metaBuilder("vdisplay").plugin(this).build(), (SimpleCommand) invocation -> {
            Player player = (Player) invocation.source();
            switch (invocation.arguments()[0]) {
              case "title" -> player.showTitle(Title.title(Component.text("Big", NamedTextColor.GOLD), Component.text("small"),
                  Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofSeconds(1))));
              case "parts" -> {
                player.sendTitlePart(TitlePart.TIMES, Title.Times.times(Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(150)));
                player.sendTitlePart(TitlePart.SUBTITLE, Component.text("part-sub"));
                player.sendTitlePart(TitlePart.TITLE, Component.text("part-title"));
              }
              case "clear" -> player.clearTitle();
              case "reset" -> player.resetTitle();
              case "actionbar" -> player.sendActionBar(Component.text("Go", NamedTextColor.GREEN));
              case "header" -> {
                player.sendPlayerListHeaderAndFooter(Component.text("Top"), Component.text("Bottom"));
                player.sendPlayerListFooter(Component.text("Bottom2"));
                signal("header:" + plain(player.getPlayerListHeader()) + ":" + plain(player.getPlayerListFooter()));
              }
              case "tablist" -> {
                TabList tab = player.getTabList();
                tab.setHeaderAndFooter(Component.text("TabTop"), Component.text("TabBottom"));
                GameProfile profile = new GameProfile(FAKE, "Fake", List.of(new GameProfile.Property("textures", "skin", "sig")));
                tab.addEntry(TabListEntry.builder().tabList(tab).profile(profile).displayName(Component.text("Shown")).latency(5).gameMode(1).build());
                signal("entries:" + tab.getEntries().size() + ":" + tab.containsEntry(FAKE) + ":"
                    + plain(tab.getEntry(FAKE).orElseThrow().getDisplayNameComponent().orElseThrow()));
              }
              case "latency" -> {
                player.getTabList().getEntry(FAKE).orElseThrow().setLatency(80);
                signal("latency:" + player.getTabList().getEntry(FAKE).orElseThrow().getLatency());
              }
              case "untab" -> signal("untab:" + player.getTabList().removeEntry(FAKE).isPresent() + ":" + player.getTabList().getEntries().size());
              case "show" -> {
                if (player.getUsername().equals("Bob")) stale = player;
                player.showBossBar(BAR);
                signal("listeners:" + listeners());
              }
              case "stale" -> { stale.showBossBar(BAR); signal("stale:" + listeners()); }
              case "update" -> {
                BAR.progress(0.25f);
                BAR.name(Component.text("Raid 2"));
                BAR.color(BossBar.Color.BLUE);
                BAR.addFlag(BossBar.Flag.DARKEN_SCREEN);
                signal("updated");
              }
              case "hide" -> {
                player.hideBossBar(BAR);
                signal("listeners:" + listeners());
                BAR.progress(1f);
                signal("hidden");
              }
              case "listeners" -> signal("listeners:" + listeners());
              case "everyone" -> {
                // The proxy and a server are audiences of players too.
                proxy.sendActionBar(Component.text("Everyone"));
                proxy.getServer("lobby").orElseThrow().showTitle(Title.title(Component.text("LobbyOnly"), Component.empty()));
                signal("everyone");
              }
              default -> { }
            }
          });
          signal("vdisplay-ready");
        }

        static String plain(Component component) { return PlainTextComponentSerializer.plainText().serialize(component); }

        /** White-box, for the test only: how many listeners the bar holds. The adapter adds one while anyone views it. */
        static int listeners() {
          try {
            java.lang.reflect.Field field = BAR.getClass().getDeclaredField("listeners");
            field.setAccessible(true);
            return ((java.util.List<?>) field.get(BAR)).size();
          } catch (ReflectiveOperationException failed) {
            return -1;
          }
        }
      }
      """;

  public static void run() throws Exception {
    VelocityCompatTests.installSignals();
    Path root = TempFiles.dir("velocity-display");
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    VelocityCompatTests.jar(VelocityCompatTests.compile(root, "vdisplay.VDisplay", PLUGIN, List.of(), true), plugins.resolve("VDisplay.jar"), null);

    try (DisplayApiTests.Server1122 lobby = new DisplayApiTests.Server1122(); DisplayApiTests.Server1122 survival = new DisplayApiTests.Server1122()) {
      ConduitConfiguration configuration = VelocityCompatTests.configuration(List.of(lobby.server("lobby"), survival.server("survival")));
      try (MinecraftProxy proxy = new MinecraftProxy(configuration, Authenticators.create(configuration.authentication()), RsaKeys.generate(), plugins)) {
        DisplayApiTests.platform("velocity-display-serve", () -> { try { proxy.serve(); } catch (IOException ignored) { } });
        VelocityCompatTests.awaitSignal("vdisplay-ready");
        Client alice = Client.join(proxy.port(), "Alice");
        Client bob = Client.join(proxy.port(), "Bob");
        try {
          alice.await(packet -> id(packet) == JOIN_GAME, "Alice's Join Game");
          bob.await(packet -> id(packet) == JOIN_GAME, "Bob's Join Game");

          // Titles: showTitle is times, subtitle, title; each part on its own; clear and reset.
          alice.chat("/vdisplay title");
          alice.await(packet -> title(packet, 0), "the title");
          List<byte[]> titles = alice.received(packet -> id(packet) == TITLE);
          require(titles.size() == 3 && action(titles.get(0)) == 3 && action(titles.get(1)) == 1 && action(titles.get(2)) == 0,
              "times, subtitle, title in 1.12.2's numbering");
          require(times(titles.get(0)).equals(List.of(10, 60, 20)), "durations become ticks: " + times(titles.get(0)));
          require(json(titles.get(2)).contains("Big") && json(titles.get(2)).contains("gold") && json(titles.get(1)).contains("small"), "title texts");
          alice.chat("/vdisplay parts");
          alice.await(packet -> title(packet, 0) && json(packet).contains("part-title"), "the title part");
          require(alice.received(packet -> title(packet, 1) && json(packet).contains("part-sub")).size() == 1, "the subtitle part");
          require(alice.received(packet -> title(packet, 3) && times(packet).equals(List.of(1, 2, 3))).size() == 1, "the times part");
          alice.chat("/vdisplay clear");
          alice.await(packet -> title(packet, 4), "clearTitle hides");
          alice.chat("/vdisplay reset");
          alice.await(packet -> title(packet, 5), "resetTitle resets");

          // Action bar: 1.12.2's Title action 2.
          alice.chat("/vdisplay actionbar");
          alice.await(packet -> title(packet, 2) && json(packet).contains("Go") && json(packet).contains("green"), "the action bar");

          // Header and footer: set together, then the footer alone keeps the header.
          alice.chat("/vdisplay header");
          VelocityCompatTests.awaitSignal("header:Top:Bottom2");
          alice.await(packet -> id(packet) == HEADER && strings(packet).equals(List.of("Top", "Bottom2")), "the footer changed alone");
          alice.chat("/vdisplay tablist");
          alice.await(packet -> id(packet) == HEADER && strings(packet).equals(List.of("TabTop", "TabBottom")), "TabList.setHeaderAndFooter");

          // Tab-list entries: built, added, updated through a fresh view, removed.
          VelocityCompatTests.awaitSignal("entries:1:true:Shown");
          alice.await(packet -> DisplayApiTests.legacyInfo(packet, PLAYER_INFO, 0), "the entry, as 1.12.2's Player Info add");
          alice.chat("/vdisplay latency");
          VelocityCompatTests.awaitSignal("latency:80");
          alice.await(packet -> DisplayApiTests.legacyInfo(packet, PLAYER_INFO, 2), "the latency update");

          alice.chat("/vdisplay untab");
          VelocityCompatTests.awaitSignal("untab:true:0");
          alice.await(packet -> DisplayApiTests.legacyInfo(packet, PLAYER_INFO, 4), "the entry's removal");

          // Boss bar: one Adventure listener however many view it; every change reaches every viewer.
          alice.chat("/vdisplay show");
          VelocityCompatTests.awaitCount("listeners:1", 1);
          bob.chat("/vdisplay show");
          VelocityCompatTests.awaitCount("listeners:1", 2);
          alice.await(packet -> boss(packet, 0) && json(bossBody(packet)).contains("Raid"), "Alice's bar");
          bob.await(packet -> boss(packet, 0), "Bob's bar");
          alice.chat("/vdisplay update");
          VelocityCompatTests.awaitSignal("updated");
          alice.await(packet -> boss(packet, 5), "the flags update");
          require(alice.received(packet -> boss(packet, 2)).size() == 1 && alice.received(packet -> boss(packet, 3)).size() == 1
              && alice.received(packet -> boss(packet, 4)).size() == 1, "progress, name and style updates, one each");
          bob.await(packet -> boss(packet, 5), "Bob sees the change too");

          // A switch sends the bar again, as it is now, after the new backend's Join Game.
          int joins = alice.received(packet -> id(packet) == JOIN_GAME).size();
          alice.chat("/server survival");
          require(NativeApiTests.waitFor(() -> DisplayApiTests.afterJoin(alice.received(packet -> true), JOIN_GAME, joins + 1,
              packet -> boss(packet, 0)), 10_000), "the bar is sent again after the switch");
          require(NativeApiTests.waitFor(() -> DisplayApiTests.afterJoin(alice.received(packet -> true), JOIN_GAME, joins + 1,
              packet -> id(packet) == HEADER), 10_000), "and so is the header");

          // With Alice on survival and Bob on lobby: the proxy's action bar reaches both, lobby's title only Bob.
          bob.chat("/vdisplay everyone");
          VelocityCompatTests.awaitSignal("everyone");
          alice.await(packet -> title(packet, 2) && json(packet).contains("Everyone"), "the proxy-wide action bar");
          bob.await(packet -> title(packet, 2) && json(packet).contains("Everyone"), "the proxy-wide action bar, for Bob too");
          bob.await(packet -> title(packet, 0) && json(packet).contains("LobbyOnly"), "the title shown to the lobby");
          Thread.sleep(200);
          require(alice.received(packet -> title(packet, 0) && json(packet).contains("LobbyOnly")).isEmpty(), "Alice is not on the lobby");

          // Alice hides it: it leaves her screen and she hears no more of it, but Bob still views it.
          alice.chat("/vdisplay hide");
          VelocityCompatTests.awaitSignal("hidden");
          alice.await(packet -> boss(packet, 1), "the bar is removed");
          VelocityCompatTests.awaitCount("listeners:1", 3);
          bob.await(packet -> boss(packet, 2) && bob.received(p -> boss(p, 2)).size() == 2, "Bob hears the change made after Alice hid it");
          Thread.sleep(200);
          require(boss(alice.received(packet -> id(packet) == BOSS).getLast(), 1), "Alice hears nothing after the remove");

          // Bob, its last viewer, disconnects: the adapter lets go of the plugin's bar.
          bob.close();
          require(NativeApiTests.waitFor(() -> {
            alice.chat("/vdisplay listeners");
            Thread.sleep(100);
            return VelocityCompatTests.count("listeners:0") >= 1;
          }, 10_000), "the bar's listener is released when its last viewer disconnects");

          // A plugin that kept Bob shows him the bar after he left: the bar must not take him back as a
          // viewer, and with him its listener and every reference to his session.
          alice.chat("/vdisplay stale");
          VelocityCompatTests.awaitSignal("stale:0");

          // And when its last viewer hides it.
          alice.chat("/vdisplay show");
          VelocityCompatTests.awaitCount("listeners:1", 4);
          alice.chat("/vdisplay hide");
          VelocityCompatTests.awaitCount("hidden", 2);
          require(VelocityCompatTests.count("listeners:0") >= 2, "the bar's listener is released when its last viewer hides it");
        } finally {
          alice.close();
          bob.close();
        }
      }
    }
    System.out.println("VelocityDisplayTests OK");
  }

  // ---------------------------------------------------------------- 1.12.2 packets

  private static int id(byte[] packet) { return NativeApiTests.id(packet); }
  private static boolean title(byte[] packet, int action) { return id(packet) == TITLE && action(packet) == action; }
  private static int action(byte[] packet) { return DisplayApiTests.titleAction(packet); }
  private static String json(byte[] packet) { return DisplayApiTests.jsonAfterAction(packet); }

  private static List<Integer> times(byte[] packet) {
    try {
      DataInputStream input = DisplayApiTests.body(packet);
      MinecraftInput.varInt(input);
      return List.of(input.readInt(), input.readInt(), input.readInt());
    } catch (IOException unreadable) {
      return List.of();
    }
  }

  /** The plain text of the header and footer JSON strings. */
  private static List<String> strings(byte[] packet) {
    try {
      DataInputStream input = DisplayApiTests.body(packet);
      List<String> texts = new ArrayList<>();
      for (int index = 0; index < 2; index++) {
        String json = MinecraftInput.string(input, 1 << 16);
        texts.add(gg.tame.conduit.text.TextCodec.fromJson(json).plain());
      }
      return texts;
    } catch (IOException unreadable) {
      return List.of();
    }
  }

  private static boolean boss(byte[] packet, int operation) {
    return id(packet) == BOSS && DisplayApiTests.bossOperation(packet) == operation;
  }

  /** A boss-bar packet with its UUID skipped, shaped like a Title packet so the action and text readers apply. */
  private static byte[] bossBody(byte[] packet) {
    byte[] shaped = new byte[packet.length - 16];
    shaped[0] = packet[0];
    System.arraycopy(packet, 17, shaped, 1, packet.length - 17);
    return shaped;
  }

  /** A scripted 1.12.2 client. Everything the proxy sends it is kept. */
  static final class Client implements AutoCloseable {
    private final Socket socket;
    private final List<byte[]> received = Collections.synchronizedList(new ArrayList<>());

    private Client(Socket socket) { this.socket = socket; }

    static Client join(int port, String name) throws IOException {
      Client client = new Client(new Socket("127.0.0.1", port));
      client.send(new Handshake(340, "localhost", port, 2).encode());
      client.send(NativeApiTests.packet(0x00, out -> MinecraftOutput.string(out, name)));
      DisplayApiTests.platform("velocity-display-client-" + name, () -> {
        try { while (true) client.received.add(MinecraftFrames.read(client.socket.getInputStream(), 1 << 20)); }
        catch (IOException ended) { }
      });
      return client;
    }

    synchronized void send(byte[] packet) throws IOException { MinecraftFrames.write(socket.getOutputStream(), packet); }
    void chat(String line) throws IOException { send(NativeApiTests.packet(0x02, out -> MinecraftOutput.string(out, line))); }
    List<byte[]> received(Predicate<byte[]> match) {
      synchronized (received) { return received.stream().filter(match).toList(); }
    }
    void await(Predicate<byte[]> match, String what) throws InterruptedException {
      if (!NativeApiTests.waitFor(() -> !received(match).isEmpty(), 10_000)) throw new AssertionError("client never got " + what);
    }
    @Override public void close() throws IOException { socket.close(); }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
