// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.auth.Authenticators;
import gg.tame.conduit.command.CommandGraphs;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.crypto.RsaKeys;
import gg.tame.conduit.network.MinecraftProxy;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.ProtocolDefinition;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What a Velocity plugin's commands look like to a 1.13+ client, through a live session: a scripted
 * 1.20.4 client joins a real proxy over a scripted backend that declares its own tree, and the tree
 * the client is sent is read back with Brigadier, the client's own parser. Covers a BrigadierCommand's
 * own nodes, CommandMeta hints on a SimpleCommand, PlayerAvailableCommandsEvent hiding a backend and
 * a proxy command and adding one, and the client's completion requests for proxy commands.
 */
public final class BrigadierTreeTests {
  public static void main(String[] a) throws Exception { run(); }

  private static final String PLUGIN = """
      package vtree;
      import com.mojang.brigadier.arguments.IntegerArgumentType;
      import com.mojang.brigadier.arguments.StringArgumentType;
      import com.mojang.brigadier.tree.RootCommandNode;
      import com.velocitypowered.api.command.BrigadierCommand;
      import com.velocitypowered.api.command.CommandManager;
      import com.velocitypowered.api.command.CommandSource;
      import com.velocitypowered.api.command.SimpleCommand;
      import com.velocitypowered.api.event.Subscribe;
      import com.velocitypowered.api.event.command.PlayerAvailableCommandsEvent;
      import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
      import com.velocitypowered.api.plugin.Plugin;
      import com.velocitypowered.api.proxy.ProxyServer;
      import javax.inject.Inject;

      @Plugin(id = "vtree", name = "VTree", version = "1.0")
      public final class VTree {
      """ + VelocityCompatTests.SIGNAL_METHOD + """
        private final ProxyServer proxy;
        @Inject public VTree(ProxyServer proxy) { this.proxy = proxy; }

        @Subscribe
        public void init(ProxyInitializeEvent event) {
          CommandManager commands = proxy.getCommandManager();
          BrigadierCommand tree = new BrigadierCommand(BrigadierCommand.literalArgumentBuilder("vbt")
              .then(BrigadierCommand.literalArgumentBuilder("add")
                  .then(BrigadierCommand.requiredArgumentBuilder("amount", IntegerArgumentType.integer(1, 64))
                      .suggests((context, builder) -> builder.suggest("16").suggest("32").buildFuture())
                      .executes(context -> { signal("vbt:" + IntegerArgumentType.getInteger(context, "amount")); return 1; }))));
          commands.register(commands.metaBuilder(tree).plugin(this).build(), tree);
          var hint = BrigadierCommand.requiredArgumentBuilder("target", StringArgumentType.word()).build();
          commands.register(commands.metaBuilder("vhint").hint(hint).plugin(this).build(), new SimpleCommand() {
            @Override public void execute(Invocation invocation) { signal("vhint:" + String.join(",", invocation.arguments())); }
            @Override public java.util.List<String> suggest(Invocation invocation) { return java.util.List.of("alpha", "beta"); }
          });
          try {
            commands.metaBuilder("vbad").hint(BrigadierCommand.literalArgumentBuilder("x").executes(context -> 1).build());
            signal("hint:accepted");
          } catch (IllegalArgumentException refused) {
            signal("hint:refused");
          }
          commands.register(commands.metaBuilder("vhidden").plugin(this).build(), (SimpleCommand) invocation -> signal("vhidden"));
          signal("vtree-ready");
        }

        @Subscribe
        @SuppressWarnings("unchecked")
        public void available(PlayerAvailableCommandsEvent event) {
          RootCommandNode<CommandSource> root = (RootCommandNode<CommandSource>) event.getRootNode();
          signal("available:" + (root.getChild("gamemode") != null) + ":" + (root.getChild("vbt") != null)
              + ":" + (root.getChild("vhidden") != null) + ":" + event.getPlayer().getUsername());
          root.removeChildByName("gamemode");
          root.removeChildByName("vhidden");
          root.addChild(BrigadierCommand.literalArgumentBuilder("vadded")
              .then(BrigadierCommand.requiredArgumentBuilder("n", IntegerArgumentType.integer(0, 9))).build());
        }
      }
      """;

  public static void run() throws Exception {
    backendNamesReadPerRelease();
    VelocityCompatTests.installSignals();
    Path root = TempFiles.dir("velocity-tree");
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    VelocityCompatTests.jar(VelocityCompatTests.compile(root, "vtree.VTree", PLUGIN, List.of(), true), plugins.resolve("VTree.jar"), null);
    aModernClientGetsThePluginsTree(plugins);
    System.out.println("BrigadierTreeTests OK");
  }

  /**
   * A backend's top-level names, which PlayerAvailableCommandsEvent needs to hide one, are behind
   * argument nodes whose properties have a length only the release's own parser numbering gives:
   * score_holder is 30 in 1.20.4 and 31 in 26.2, and read with the other's table the walk misreads
   * and must say so rather than name the wrong command.
   */
  private static void backendNamesReadPerRelease() throws Exception {
    // score_holder, time, resource: the parsers whose ids moved between these two releases.
    byte[] v765 = vanillaLikeTree(765, 30, 41, 44);
    byte[] v776 = vanillaLikeTree(776, 31, 43, 46);
    List<String> expected = List.of("scoreboard", "tick", "give", "gamemode", "warp");
    require(expected.equals(CommandGraphs.rootNames(ProtocolDefinition.forVersion(765), v765)),
        "1.20.4's tree names its commands, got " + CommandGraphs.rootNames(ProtocolDefinition.forVersion(765), v765));
    require(expected.equals(CommandGraphs.rootNames(ProtocolDefinition.forVersion(776), v776)),
        "26.2's tree names its commands, got " + CommandGraphs.rootNames(ProtocolDefinition.forVersion(776), v776));
    require(CommandGraphs.rootNames(ProtocolDefinition.forVersion(776), v765) == null,
        "a 1.20.4 tree read with 26.2's numbering is refused, not misread");
  }

  private static void aModernClientGetsThePluginsTree(Path plugins) throws Exception {
    ProtocolDefinition p = ProtocolDefinition.forVersion(765);
    byte finishOut = (byte) p.id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_FINISH);
    byte finishIn = (byte) p.id(ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_FINISH);
    int declare = p.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DECLARE_COMMANDS);
    int chatCommand = p.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT_COMMAND);
    int tabRequest = p.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_TAB_COMPLETE_REQUEST);
    int tabReply = p.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_TAB_COMPLETE);
    try (ServerSocket listener = new ServerSocket(0)) {
      DisplayApiTests.platform("velocity-tree-backend", () -> backend(listener, finishOut, finishIn, declare));
      ConduitConfiguration configuration = VelocityCompatTests.configuration(
          List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", listener.getLocalPort()))));
      try (MinecraftProxy proxy = new MinecraftProxy(configuration, Authenticators.create(configuration.authentication()), RsaKeys.generate(), plugins)) {
        DisplayApiTests.platform("velocity-tree-serve", () -> { try { proxy.serve(); } catch (IOException ignored) { } });
        VelocityCompatTests.awaitSignal("vtree-ready");
        VelocityCompatTests.awaitSignal("hint:refused");
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
          byte[] tree = until(in, declare);
          VelocityCompatTests.awaitSignal("available:true:true:true:playr");

          var dispatcher = CommandApiTests.clientDispatcher(tree);
          List<String> roots = new ArrayList<>();
          for (var node : dispatcher.getRoot().getChildren()) roots.add(node.getName());
          for (String name : List.of("warp", "vbt", "vhint", "vadded", "server")) require(roots.contains(name), "the client is sent /" + name + ", got " + roots);
          require(!roots.contains("gamemode"), "the backend command the plugin removed is not sent, got " + roots);
          require(!roots.contains("vhidden"), "the proxy command the plugin removed is not sent, got " + roots);
          for (String accepted : List.of("vbt add 5", "vadded 3", "vhint Steve", "vhint any words at all", "warp spawn")) {
            var parse = dispatcher.parse(accepted, new Object());
            require(parse.getReader().getRemainingLength() == 0 && parse.getExceptions().isEmpty(), "the client parses /" + accepted);
          }
          for (String refused : List.of("vbt add 99", "vadded 12", "vbt remove")) {
            var parse = dispatcher.parse(refused, new Object());
            require(parse.getReader().getRemainingLength() > 0 || !parse.getExceptions().isEmpty(), "/" + refused + " does not fit the tree");
          }
          List<String> hinted = new ArrayList<>();
          for (var node : dispatcher.getRoot().getChild("vhint").getChildren()) hinted.add(node.getName());
          require(hinted.equals(List.of("target", "arguments")), "the hint sits next to the greedy argument, got " + hinted);

          // Completion requests for proxy commands are answered by the proxy, from the plugin.
          MinecraftFrames.write(out, tabRequest(tabRequest, 7, "/vbt add "));
          require(matches(until(in, tabReply), 7).equals(List.of("16", "32")), "the Brigadier command's own suggestions");
          MinecraftFrames.write(out, tabRequest(tabRequest, 8, "/vhint a"));
          require(matches(until(in, tabReply), 8).equals(List.of("alpha")), "a SimpleCommand's suggest, filtered by what was typed");

          MinecraftFrames.write(out, ModLoaderTests.command(chatCommand, "vbt add 5"));
          VelocityCompatTests.awaitSignal("vbt:5");
        }
      }
    }
  }

  /** A 1.20.4 backend: login, an empty Configuration, Join Game, then its own command tree. */
  private static void backend(ServerSocket listener, byte finishOut, byte finishIn, int declare) {
    var login = AllTests.acceptLoginUnchecked(listener, 765);
    try (Socket socket = login.getKey()) {
      socket.setSoTimeout(15_000);
      InputStream in = socket.getInputStream();
      OutputStream out = socket.getOutputStream();
      MinecraftFrames.read(in, 8192);
      ByteArrayOutputStream success = new ByteArrayOutputStream();
      try (DataOutputStream data = new DataOutputStream(success)) {
        MinecraftOutput.varInt(data, 0x02);
        data.writeLong(0L);
        data.writeLong(0L);
        MinecraftOutput.string(data, "playr");
        MinecraftOutput.varInt(data, 0);
      }
      MinecraftFrames.write(out, success.toByteArray());
      MinecraftFrames.read(in, 8192);
      MinecraftFrames.write(out, new byte[] {finishOut});
      byte[] packet;
      do { packet = MinecraftFrames.read(in, 8192); } while (!(packet.length == 1 && packet[0] == finishIn));
      MinecraftFrames.write(out, ModLoaderTests.joinGame765());
      MinecraftFrames.write(out, backendTree(declare));
      while (true) MinecraftFrames.read(in, 8192);
    } catch (Exception closed) {
      // the proxy closing is the end of this backend
    }
  }

  /** /gamemode <mode> and /warp <name> [count 1..10]: Brigadier parsers only, so the client parser here can read them. */
  private static byte[] backendTree(int packetId) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(out, packetId);
      MinecraftOutput.varInt(out, 6);
      node(out, 0x00, List.of(1, 3), null);
      node(out, 0x01, List.of(2), "gamemode");
      node(out, 0x06, List.of(), "mode");
      MinecraftOutput.varInt(out, 5);
      MinecraftOutput.varInt(out, 0);
      node(out, 0x01, List.of(4), "warp");
      node(out, 0x06, List.of(5), "name");
      MinecraftOutput.varInt(out, 5);
      MinecraftOutput.varInt(out, 0);
      node(out, 0x06, List.of(), "count");
      MinecraftOutput.varInt(out, 3);
      out.writeByte(0x03);
      out.writeInt(1);
      out.writeInt(10);
      MinecraftOutput.varInt(out, 0);
    }
    return bytes.toByteArray();
  }

  /**
   * A tree shaped like vanilla's where it matters: argument nodes whose properties are not
   * Brigadier's, at the given release's ids, ahead of the plain literals.
   */
  private static byte[] vanillaLikeTree(int version, int scoreHolder, int time, int resource) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(out, ProtocolDefinition.forVersion(version).id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT,
          PacketKind.PLAY_DECLARE_COMMANDS));
      MinecraftOutput.varInt(out, 9);
      node(out, 0x00, List.of(1, 3, 5, 7, 8), null);
      node(out, 0x01, List.of(2), "scoreboard");
      node(out, 0x06, List.of(), "targets");
      MinecraftOutput.varInt(out, scoreHolder);
      out.writeByte(1);
      node(out, 0x01, List.of(4), "tick");
      node(out, 0x06, List.of(), "time");
      MinecraftOutput.varInt(out, time);
      out.writeInt(0);
      node(out, 0x01, List.of(6), "give");
      node(out, 0x16, List.of(), "item");
      MinecraftOutput.varInt(out, resource);
      MinecraftOutput.string(out, "minecraft:item");
      MinecraftOutput.string(out, "minecraft:ask_server");
      node(out, 0x05, List.of(), "gamemode");
      node(out, 0x05, List.of(), "warp");
      MinecraftOutput.varInt(out, 0);
    }
    return bytes.toByteArray();
  }

  /** A node's flags, children and name; the caller writes the parser and properties that follow. */
  private static void node(DataOutputStream out, int flags, List<Integer> children, String name) throws IOException {
    out.writeByte(flags);
    MinecraftOutput.varInt(out, children.size());
    for (int child : children) MinecraftOutput.varInt(out, child);
    if (name != null) MinecraftOutput.string(out, name);
  }

  private static byte[] tabRequest(int id, int transaction, String text) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(out, id);
      MinecraftOutput.varInt(out, transaction);
      MinecraftOutput.string(out, text);
    }
    return bytes.toByteArray();
  }

  /** The matches of a 1.13+ completion reply, after checking it answers {@code transaction}. */
  private static List<String> matches(byte[] packet, int transaction) throws IOException {
    DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet));
    MinecraftInput.varInt(input);
    require(MinecraftInput.varInt(input) == transaction, "the reply answers request " + transaction);
    MinecraftInput.varInt(input);
    MinecraftInput.varInt(input);
    int count = MinecraftInput.varInt(input);
    List<String> found = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      found.add(MinecraftInput.string(input, 32767));
      if (input.readBoolean()) MinecraftInput.string(input, 1 << 20);
    }
    return found;
  }

  private static byte[] until(InputStream in, int id) throws IOException {
    while (true) {
      byte[] packet = MinecraftFrames.read(in, 1 << 20);
      if (packet.length > 0 && (packet[0] & 0xFF) == id) return packet;
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
