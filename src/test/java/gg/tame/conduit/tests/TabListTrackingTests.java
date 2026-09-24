// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.NativeApiTests.packet;

import gg.tame.conduit.api.player.TabListEntry;
import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.auth.Authenticators;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.crypto.RsaKeys;
import gg.tame.conduit.network.MinecraftProxy;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.DisplayPackets;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.session.ClientDisplay;
import gg.tame.conduit.text.TextCodec;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The backend's tab-list entries, read as they pass, beside the proxy's own. First the reader and the
 * display on their own: a pre-1.19.3 Player Info, the display's own packets not read back as the
 * backend's, a Join Game starting the record afresh, and 1.7's list named by text. Then end to end: a
 * plugin compiled against the real velocity-api reads and edits the entries a scripted backend sends
 * a scripted client, at 1.20.4 (765), 1.20.1 (763) and 1.7 (5), each on a pair of backends of its own
 * release, switching from one to the other.
 */
public final class TabListTrackingTests {
  public static void main(String[] a) throws Exception { run(); }

  private static final String PLAYER = "Tracker";

  private static final String PLUGIN = """
      package vtab;
      import com.velocitypowered.api.command.SimpleCommand;
      import com.velocitypowered.api.event.Subscribe;
      import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
      import com.velocitypowered.api.plugin.Plugin;
      import com.velocitypowered.api.proxy.Player;
      import com.velocitypowered.api.proxy.ProxyServer;
      import com.velocitypowered.api.proxy.player.TabList;
      import com.velocitypowered.api.proxy.player.TabListEntry;
      import com.velocitypowered.api.util.GameProfile;
      import java.util.Comparator;
      import java.util.List;
      import java.util.UUID;
      import java.util.stream.Collectors;
      import javax.inject.Inject;
      import net.kyori.adventure.text.Component;
      import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

      @Plugin(id = "vtab", name = "VTab", version = "1.0")
      public final class VTab {
      """ + VelocityCompatTests.SIGNAL_METHOD + """
        static final UUID FAKE = new UUID(0x1234, 0x5678);
        private final ProxyServer proxy;
        @Inject public VTab(ProxyServer proxy) { this.proxy = proxy; }

        @Subscribe
        public void init(ProxyInitializeEvent event) {
          var commands = proxy.getCommandManager();
          commands.register(commands.metaBuilder("vtab").plugin(this).build(), (SimpleCommand) invocation -> {
            Player player = (Player) invocation.source();
            TabList tab = player.getTabList();
            switch (invocation.arguments()[0]) {
              // Every entry but the player's own, which the proxy may add for a client that can lose it.
              case "list" -> signal("list:" + tab.getEntries().stream()
                  .filter(entry -> !entry.getProfile().getName().equals(player.getUsername()))
                  .sorted(Comparator.comparing(entry -> entry.getProfile().getName()))
                  .map(entry -> entry.getProfile().getName() + "/" + entry.getLatency() + "/" + entry.getGameMode() + "/"
                      + entry.getDisplayNameComponent().map(VTab::plain).orElse("-"))
                  .collect(Collectors.joining(",")));
              case "add" -> {
                tab.addEntry(TabListEntry.builder().tabList(tab).profile(new GameProfile(FAKE, "Fake", List.of()))
                    .displayName(Component.text("Shown")).latency(5).gameMode(1).build());
                signal("added:" + tab.containsEntry(FAKE));
              }
              case "edit" -> {
                named(tab, "Steve").setDisplayName(Component.text("Edited")).setLatency(123).setGameMode(3);
                TabListEntry fresh = tab.getEntry(named(tab, "Steve").getProfile().getId()).orElseThrow();
                signal("edited:" + fresh.getLatency() + ":" + fresh.getGameMode());
              }
              case "remove" -> {
                UUID alex = named(tab, "Alex").getProfile().getId();
                signal("removed:" + tab.removeEntry(alex).isPresent() + ":" + tab.containsEntry(alex));
              }
              default -> { }
            }
          });
          signal("vtab-ready");
        }

        static TabListEntry named(TabList tab, String name) {
          return tab.getEntries().stream().filter(entry -> entry.getProfile().getName().equals(name)).findFirst().orElseThrow();
        }

        static String plain(Component component) { return PlainTextComponentSerializer.plainText().serialize(component); }
      }
      """;

  public static void run() throws Exception {
    aLegacyPlayerInfoIsRead();
    theDisplaysOwnPacketsAreNotTheBackends();
    a17ClientListsTheProxysEntriesByText();
    ownTexturesKeepA1201DisplayName();

    Path root = TempFiles.dir("tablist-tracking");
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    VelocityCompatTests.jar(VelocityCompatTests.compile(root, "vtab.VTab", PLUGIN, List.of(), true), plugins.resolve("VTab.jar"), null);
    for (int protocol : new int[] {765, 763, 5}) aPluginSeesAndEditsTheBackendsEntries(protocol, plugins);
    System.out.println("TabListTrackingTests OK");
  }

  // ---------------------------------------------------------------- the reader and the display

  private static final UUID STEVE = UUID.nameUUIDFromBytes("Steve".getBytes(StandardCharsets.UTF_8));

  /** 1.12.2's Player Info, one action per packet: add, then each field, then remove. */
  private static void aLegacyPlayerInfoIsRead() throws Exception {
    ProtocolDefinition p = ProtocolDefinition.forVersion(340);
    Map<UUID, TabListEntry> entries = new LinkedHashMap<>();
    DisplayPackets.readPlayerInfo(p, packet(0x2E, out -> {
      MinecraftOutput.varInt(out, 0); MinecraftOutput.varInt(out, 1); uuid(out, STEVE);
      MinecraftOutput.string(out, "Steve");
      MinecraftOutput.varInt(out, 1); MinecraftOutput.string(out, "textures"); MinecraftOutput.string(out, "skin"); out.writeBoolean(false);
      MinecraftOutput.varInt(out, 2); MinecraftOutput.varInt(out, 40); out.writeBoolean(false);
    }), entries);
    TabListEntry added = entries.get(STEVE);
    require(added != null && added.name().equals("Steve") && added.gameMode() == 2 && added.latency() == 40 && added.listed()
        && added.properties().equals(List.of(new TabListEntry.Property("textures", "skin", null))), "the add: " + added);
    DisplayPackets.readPlayerInfo(p, packet(0x2E, out -> {
      MinecraftOutput.varInt(out, 2); MinecraftOutput.varInt(out, 1); uuid(out, STEVE); MinecraftOutput.varInt(out, 90);
    }), entries);
    DisplayPackets.readPlayerInfo(p, packet(0x2E, out -> {
      MinecraftOutput.varInt(out, 3); MinecraftOutput.varInt(out, 1); uuid(out, STEVE); out.writeBoolean(true);
      MinecraftOutput.string(out, "{\"text\":\"Captain\"}");
    }), entries);
    DisplayPackets.readPlayerInfo(p, packet(0x2E, out -> {
      MinecraftOutput.varInt(out, 1); MinecraftOutput.varInt(out, 1); uuid(out, new UUID(7, 7)); MinecraftOutput.varInt(out, 1);
    }), entries);
    require(entries.size() == 1 && entries.get(STEVE).latency() == 90 && entries.get(STEVE).displayName().plain().equals("Captain"),
        "latency and display name updates; an update for an entry never added is skipped: " + entries);
    DisplayPackets.readPlayerInfo(p, packet(0x2E, out -> {
      MinecraftOutput.varInt(out, 4); MinecraftOutput.varInt(out, 1); uuid(out, STEVE);
    }), entries);
    require(entries.isEmpty(), "the remove");
  }

  /**
   * At 765: the display's own entries pass the same write path as the backend's but are not recorded
   * as the backend's; a backend packet is; the next Join Game starts the record afresh.
   */
  private static void theDisplaysOwnPacketsAreNotTheBackends() throws Exception {
    ProtocolDefinition p = ProtocolDefinition.forVersion(765);
    List<byte[]> wire = new ArrayList<>();
    ClientDisplay[] display = new ClientDisplay[1];
    // The output is the session's writeClient: it passes every packet back through afterWrite.
    display[0] = new ClientDisplay(DisplayApiTests.dummyPlayer(), p, packet -> {
      wire.add(packet);
      display[0].afterWrite(ConnectionState.PLAY, packet);
    });
    byte[] joinGame = PlayPackets.idOnly(0x29);
    enter(display[0], joinGame);
    display[0].addEntry(new TabListEntry(new UUID(1, 1), "Own", List.of(), null, 1, 0, true, 0, true));
    require(wire.size() == 1 && display[0].backendEntries().isEmpty(), "the proxy's own add is not the backend's");
    display[0].afterWrite(ConnectionState.PLAY, splitAdd(p, List.of(new Row("Steve", 0, 10, null))));
    require(display[0].backendEntries().size() == 1 && display[0].backendEntries().get(0).name().equals("Steve"), "the backend's add is");
    require(display[0].updateBackendEntry(new TabListEntry(STEVE, "ignored", List.of(), Text.of("Edited"), 99, 3, true, 0, true)),
        "the backend's entry can be changed");
    TabListEntry changed = display[0].backendEntries().get(0);
    require(changed.name().equals("Steve") && changed.latency() == 99 && changed.gameMode() == 3, "the profile stays the backend's: " + changed);
    byte[] update = wire.getLast();
    require(PlayPackets.packetId(update) == 0x3C && update[1] == (0x04 | 0x10 | 0x20), "one update, game mode, latency and display name");
    require(!display[0].updateBackendEntry(new TabListEntry(new UUID(5, 5), "Nobody", List.of(), null, 1, 0, true, 0, true)),
        "an id the backend does not have is refused");
    enter(display[0], joinGame);
    require(display[0].backendEntries().isEmpty() && display[0].entries().size() == 1, "a new backend's Join Game starts its record afresh");
    display[0].close();
  }

  /** 1.7 names an entry by its text: the proxy's is listed under its display name, and renamed as a remove and an add. */
  private static void a17ClientListsTheProxysEntriesByText() throws Exception {
    ProtocolDefinition p = ProtocolDefinition.forVersion(5);
    List<byte[]> wire = new ArrayList<>();
    ClientDisplay display = new ClientDisplay(DisplayApiTests.dummyPlayer(), p, wire::add);
    UUID id = new UUID(3, 3);
    display.addEntry(new TabListEntry(id, "Fake", List.of(), Text.of("Shown"), 5, 1, true, 0, true));
    require(wire.isEmpty(), "nothing before Join Game");
    enter(display, PlayPackets.idOnly(0x01));
    require(wire.size() == 1 && legacyItem(wire.get(0)).equals("Shown:true:5"), "listed under its display name after Join Game");
    wire.clear();
    display.addEntry(new TabListEntry(id, "Fake", List.of(), null, 5, 1, true, 0, true));
    require(wire.size() == 2 && legacyItem(wire.get(0)).equals("Shown:false:0") && legacyItem(wire.get(1)).equals("Fake:true:5"),
        "a new text is the old one taken off and the new one listed");
    wire.clear();
    require(display.removeEntry(id) && wire.size() == 1 && legacyItem(wire.get(0)).equals("Fake:false:0"), "removed by its text");
    display.close();
  }

  /**
   * The player's own textures are put into a backend's add of them; before 1.20.3 a display name in
   * the same packet is a JSON string, which must be copied as one (it was read as NBT).
   */
  private static void ownTexturesKeepA1201DisplayName() throws Exception {
    ProtocolDefinition p = ProtocolDefinition.forVersion(763);
    var textures = new gg.tame.conduit.login.ProfileProperty("textures", "skin", java.util.Optional.of("sig"));
    var profile = new gg.tame.conduit.login.PlayerProfile(STEVE, "Steve", List.of(textures), true);
    byte[] rewritten = gg.tame.conduit.protocol.PlayerInfoUpdate.ensureOwnTextures(p, splitAdd(p, List.of(new Row("Steve", 0, 10, "Captain"))), profile);
    Map<UUID, TabListEntry> entries = new LinkedHashMap<>();
    DisplayPackets.readPlayerInfo(p, rewritten, entries);
    TabListEntry steve = entries.get(STEVE);
    require(steve != null && steve.properties().equals(List.of(new TabListEntry.Property("textures", "skin", "sig")))
        && steve.displayName().plain().equals("Captain"), "textures put in, the JSON display name kept: " + steve);
  }

  private static void enter(ClientDisplay display, byte[] packet) {
    display.beforeWrite(ConnectionState.PLAY, packet);
    display.afterWrite(ConnectionState.PLAY, packet);
  }

  // ---------------------------------------------------------------- end to end

  private static void aPluginSeesAndEditsTheBackendsEntries(int protocol, Path plugins) throws Exception {
    ProtocolDefinition p = ProtocolDefinition.forVersion(protocol);
    boolean legacy = protocol == 5;
    String where = "protocol " + protocol + ": ";
    // Each release starts from no signals, so one release's cannot answer for the next's.
    VelocityCompatTests.installSignals();
    try (Backend lobby = new Backend(protocol); Backend survival = new Backend(protocol)) {
      ConduitConfiguration configuration = VelocityCompatTests.configuration(List.of(lobby.server("lobby"), survival.server("survival")));
      try (MinecraftProxy proxy = new MinecraftProxy(configuration, Authenticators.create(configuration.authentication()), RsaKeys.generate(), plugins)) {
        DisplayApiTests.platform("tablist-serve-" + protocol, () -> { try { proxy.serve(); } catch (IOException ignored) { } });
        VelocityCompatTests.awaitSignal("vtab-ready");
        try (Client client = Client.join(p, proxy.port())) {
          client.await(packet -> id(packet) == joinGameId(p), where + "Join Game");
          require(NativeApiTests.waitFor(() -> lobby.current != null, 10_000), where + "the lobby has the player");

          lobby.send(add(p, List.of(new Row("Steve", 0, 10, null), new Row("Alex", 1, 20, "Alex!"))));
          lobby.send(latency(p, "Steve", 50));
          awaitList(client, legacy ? "Alex/20/0/-,Steve/50/0/-" : "Alex/20/1/Alex!,Steve/50/0/-", where + "the backend's entries");

          client.command("vtab add");
          VelocityCompatTests.awaitSignal("added:true");
          String fake = "Fake/5/1/Shown";
          awaitList(client, legacy ? "Alex/20/0/-," + fake + ",Steve/50/0/-" : "Alex/20/1/Alex!," + fake + ",Steve/50/0/-", where + "and the proxy's");

          client.command("vtab edit");
          VelocityCompatTests.awaitSignal("edited:123:3");
          UUID steve = legacy ? DisplayPackets.legacyListId("Steve") : id("Steve");
          require(NativeApiTests.waitFor(() -> {
            TabListEntry seen = client.view(p).get(steve);
            return seen != null && seen.latency() == 123 && (legacy || seen.gameMode() == 3 && seen.displayName() != null
                && seen.displayName().plain().equals("Edited"));
          }, 10_000), where + "the edit of the backend's entry reaches the client: " + client.view(p).get(steve));

          client.command("vtab remove");
          VelocityCompatTests.awaitSignal("removed:true:false");
          UUID alex = legacy ? DisplayPackets.legacyListId("Alex") : id("Alex");
          require(NativeApiTests.waitFor(() -> !client.view(p).containsKey(alex), 10_000), where + "the backend's entry is removed from the client");

          lobby.send(latency(p, "Steve", 60));
          awaitList(client, fake + ",Steve/60/3/Edited", where + "the backend's next update replaces only its own field");

          // A switch: the new backend's list replaces the old one's; the proxy's entry stays.
          client.command("server survival");
          if (protocol >= 764) client.reconfigure(p);
          require(NativeApiTests.waitFor(() -> survival.current != null, 10_000), where + "switched");
          survival.send(add(p, List.of(new Row("Notch", 0, 7, null))));
          awaitList(client, fake + ",Notch/7/0/-", where + "after the switch, the new backend's entries");
        }
      }
    }
  }

  private static void awaitList(Client client, String expected, String what) throws Exception {
    String signal = "list:" + expected;
    require(NativeApiTests.waitFor(() -> {
      client.command("vtab list");
      Thread.sleep(100);
      return VelocityCompatTests.count(signal) > 0;
    }, 10_000), what + ", expected " + expected);
  }

  // ---------------------------------------------------------------- packets by release

  private record Row(String name, int gameMode, int latency, String display) {}

  private static UUID id(String name) { return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)); }

  private static int id(byte[] packet) { return NativeApiTests.id(packet); }

  private static int joinGameId(ProtocolDefinition p) { return p.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN); }

  private static int infoId(ProtocolDefinition p) {
    return p.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_INFO_UPDATE);
  }

  /** The backend adding entries: 1.7 one Player List Item each, 1.19.3+ one Player Info Update with a (null) chat session in it. */
  private static List<byte[]> add(ProtocolDefinition p, List<Row> rows) throws IOException {
    if (p.version().number() == 5) {
      List<byte[]> items = new ArrayList<>();
      for (Row row : rows) items.add(DisplayPackets.legacyListItem(p, row.name(), true, row.latency()));
      return items;
    }
    return List.of(splitAdd(p, rows));
  }

  private static byte[] splitAdd(ProtocolDefinition p, List<Row> rows) throws IOException {
    int number = p.version().number();
    return packet(infoId(p), out -> {
      out.writeByte(0x3F);
      MinecraftOutput.varInt(out, rows.size());
      for (Row row : rows) {
        uuid(out, id(row.name()));
        MinecraftOutput.string(out, row.name());
        MinecraftOutput.varInt(out, 0);
        out.writeBoolean(false);
        MinecraftOutput.varInt(out, row.gameMode());
        out.writeBoolean(true);
        MinecraftOutput.varInt(out, row.latency());
        out.writeBoolean(row.display() != null);
        if (row.display() != null) TextCodec.write(out, Text.of(row.display()), number);
      }
    });
  }

  private static List<byte[]> latency(ProtocolDefinition p, String name, int latency) throws IOException {
    if (p.version().number() == 5) return List.of(DisplayPackets.legacyListItem(p, name, true, latency));
    return List.of(packet(infoId(p), out -> {
      out.writeByte(0x10); MinecraftOutput.varInt(out, 1); uuid(out, id(name)); MinecraftOutput.varInt(out, latency);
    }));
  }

  private static String legacyItem(byte[] packet) throws IOException {
    var input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(PlayPackets.body(packet)));
    return MinecraftInput.string(input, 64) + ":" + input.readBoolean() + ":" + input.readShort();
  }

  private static void uuid(java.io.DataOutputStream out, UUID id) throws IOException {
    out.writeLong(id.getMostSignificantBits());
    out.writeLong(id.getLeastSignificantBits());
  }

  /** Join Game as each release's client reads it, with as little world as it takes. */
  private static byte[] joinGame(int protocol) throws Exception {
    return switch (protocol) {
      case 765 -> ModLoaderTests.joinGame765();
      // 1.20.1: entity, hardcore, game mode, previous, worlds, an empty registry codec, type, world,
      // seed, max players, view and simulation distance, four flags, no death location, portal cooldown.
      case 763 -> packet(0x28, out -> {
        out.writeInt(7); out.writeBoolean(false); out.writeByte(0); out.writeByte(-1);
        MinecraftOutput.varInt(out, 1); MinecraftOutput.string(out, "minecraft:overworld");
        out.writeByte(0x0A); out.writeShort(0); out.writeByte(0);
        MinecraftOutput.string(out, "minecraft:overworld"); MinecraftOutput.string(out, "minecraft:overworld");
        out.writeLong(0L); MinecraftOutput.varInt(out, 20); MinecraftOutput.varInt(out, 10); MinecraftOutput.varInt(out, 10);
        out.writeBoolean(false); out.writeBoolean(true); out.writeBoolean(false); out.writeBoolean(false); out.writeBoolean(false);
        MinecraftOutput.varInt(out, 0);
      });
      // 1.7: entity, game mode, dimension, difficulty, max players, level type.
      default -> packet(0x01, out -> {
        out.writeInt(7); out.writeByte(0); out.writeByte(0); out.writeByte(1); out.writeByte(20); MinecraftOutput.string(out, "default");
      });
    };
  }

  // ---------------------------------------------------------------- scripted peers

  /** A scripted backend of one release: logs the player in (1.20.2+ through Configuration), sends Join Game, then listens. */
  static final class Backend implements AutoCloseable {
    private final int protocol;
    private final ServerSocket listener = new ServerSocket(0);
    private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<>());
    volatile Socket current;

    Backend(int protocol) throws IOException {
      this.protocol = protocol;
      DisplayApiTests.platform("tablist-backend-" + protocol, () -> {
        try {
          while (true) {
            var login = AllTests.acceptLoginUnchecked(listener, protocol);
            sockets.add(login.getKey());
            DisplayApiTests.platform("tablist-backend-session", () -> serve(login.getKey()));
          }
        } catch (RuntimeException closed) { }
      });
    }

    BackendServer server(String name) { return new BackendServer(name, new InetSocketAddress("127.0.0.1", listener.getLocalPort())); }

    private void serve(Socket socket) {
      try (socket) {
        InputStream in = socket.getInputStream();
        String name = MinecraftInput.string(DisplayApiTests.body(MinecraftFrames.read(in, 4096)), 64);
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        write(socket, packet(0x02, out -> {
          if (protocol == 5) {
            MinecraftOutput.string(out, uuid.toString());
            MinecraftOutput.string(out, name);
          } else {
            uuid(out, uuid);
            MinecraftOutput.string(out, name);
            MinecraftOutput.varInt(out, 0);
          }
        }));
        if (protocol >= 764) {
          MinecraftFrames.read(in, 8192);                  // Login Acknowledged
          write(socket, new byte[] {0x02});                // Finish Configuration
          byte[] packet;
          do packet = MinecraftFrames.read(in, 1 << 16); while (!(packet.length == 1 && packet[0] == 0x02));
        }
        write(socket, joinGame(protocol));
        current = socket;
        while (true) MinecraftFrames.read(in, 1 << 20);
      } catch (Exception ended) { }
    }

    void send(List<byte[]> packets) throws IOException { for (byte[] packet : packets) write(current, packet); }

    private static void write(Socket socket, byte[] packet) throws IOException {
      synchronized (socket) { MinecraftFrames.write(socket.getOutputStream(), packet); }
    }

    @Override public void close() throws IOException {
      listener.close();
      synchronized (sockets) { for (Socket socket : sockets) socket.close(); }
    }
  }

  /** A scripted client of one release. Everything the proxy sends it after Login Success is kept. */
  static final class Client implements AutoCloseable {
    private final ProtocolDefinition protocol;
    private final Socket socket;
    private final List<byte[]> received = Collections.synchronizedList(new ArrayList<>());

    private Client(ProtocolDefinition protocol, Socket socket) { this.protocol = protocol; this.socket = socket; }

    static Client join(ProtocolDefinition p, int port) throws Exception {
      int number = p.version().number();
      Client client = new Client(p, new Socket("127.0.0.1", port));
      client.socket.setSoTimeout(15_000);
      InputStream in = client.socket.getInputStream();
      client.send(new Handshake(number, "localhost", port, 2).encode());
      client.send(packet(0x00, out -> {
        MinecraftOutput.string(out, PLAYER);
        if (number >= 764) uuid(out, new UUID(0, 0));
        else if (number == 763) out.writeBoolean(false);
      }));
      require(MinecraftFrames.read(in, 8192)[0] == 0x02, "Login Success");
      if (number >= 764) {
        client.send(new byte[] {0x03});
        ModLoaderTests.readConfiguration(in, (byte) 0x02);
        client.send(new byte[] {0x02});
      }
      DisplayApiTests.platform("tablist-client-" + number, () -> {
        try { while (true) client.received.add(MinecraftFrames.read(in, 1 << 20)); }
        catch (IOException ended) { }
      });
      return client;
    }

    boolean legacy() { return protocol.version().number() == 5; }

    synchronized void send(byte[] packet) throws IOException { MinecraftFrames.write(socket.getOutputStream(), packet); }

    /**
     * A command, typed without its slash: 1.7 sends it as a chat line with one, 1.19.3+ as a Chat
     * Command without, unsigned (timestamp, salt, no argument signatures, nothing acknowledged).
     */
    void command(String line) throws IOException {
      if (legacy()) send(packet(0x01, out -> MinecraftOutput.string(out, "/" + line)));
      else send(packet(protocol.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT_COMMAND), out -> {
        MinecraftOutput.string(out, line);
        out.writeLong(0L); out.writeLong(0L);
        MinecraftOutput.varInt(out, 0); MinecraftOutput.varInt(out, 0);
        out.write(new byte[3]);
      }));
    }

    /** Answers a switch's Start Configuration and Finish Configuration, as a 1.20.2+ client does. */
    void reconfigure(ProtocolDefinition p) throws Exception {
      int start = p.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_START_CONFIGURATION);
      await(packet -> id(packet) == start, "Start Configuration");
      int from = received.size();
      send(PlayPackets.idOnly(p.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CONFIGURATION_ACKNOWLEDGED)));
      require(NativeApiTests.waitFor(() -> {
        synchronized (received) { return received.subList(from, received.size()).stream().anyMatch(packet -> packet.length == 1 && packet[0] == 0x02); }
      }, 10_000), "Finish Configuration after the switch");
      send(new byte[] {0x02});
    }

    /** The client's list as the Player Info packets it was sent would leave it. */
    Map<UUID, TabListEntry> view(ProtocolDefinition p) throws IOException {
      int update = infoId(p);
      int remove = p.defines(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_INFO_REMOVE)
          ? p.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_INFO_REMOVE) : -1;
      Map<UUID, TabListEntry> view = new LinkedHashMap<>();
      for (byte[] packet : received(packet -> id(packet) == update || id(packet) == remove)) DisplayPackets.readPlayerInfo(p, packet, view);
      return view;
    }

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
