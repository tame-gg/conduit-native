// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.command;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolDefinition;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class CommandGraphs {
  private CommandGraphs() {}
  /**
   * Appends the proxy commands to a backend command tree without decoding it.
   *
   * <p>Decoding the whole tree means decoding every argument node's property payload, keyed by a
   * numeric parser id whose meaning changes between versions: 26.2 dropped {@code brigadier:float},
   * shifting every id down by one, so a 1.20.4 table desynchronizes and runs off the end. None of
   * that is needed here. Appending only requires the root node's child list, and a root node is a
   * flags byte plus that list — no name, no parser, no properties. New nodes go on the end so no
   * existing index moves, and every byte between the root node and the trailing root index is
   * copied verbatim.
   *
   * <p>The trailing root index is the last varint in the packet, recovered by walking backwards
   * over its continuation bytes. Anything unexpected throws and the caller forwards the backend
   * packet unchanged.
   */
  public static byte[] mergeProxyCommands(ProtocolDefinition protocol, byte[] packet, List<String> serverNames) throws IOException {
    return mergeProxyCommands(protocol, packet, serverNames, List.of());
  }
  /**
   * As above, plus a childless literal for every name in {@code extraNames} the proxy commands do
   * not already cover -- what {@link CommandManager#names()} returns, so a plugin's command and its
   * aliases are in the tree a 1.13+ client parses against instead of being highlighted as unknown.
   */
  public static byte[] mergeProxyCommands(ProtocolDefinition protocol, byte[] packet, List<String> serverNames,
      List<String> extraNames) throws IOException {
    return mergeProxyCommands(protocol, packet, serverNames, extraNames, java.util.Set.of());
  }
  /** As above, with {@code displaced} the built-in names a plugin holds (CommandManager#displacedBuiltIns). */
  public static byte[] mergeProxyCommands(ProtocolDefinition protocol, byte[] packet, List<String> serverNames,
      List<String> extraNames, java.util.Set<String> displaced) throws IOException {
    return mergeProxyCommands(protocol, packet, serverNames, extraNames, displaced, name -> true);
  }
  /**
   * As above, declaring only the commands {@code shown} accepts (CommandManager#shownTo): a top-level
   * name, or {@code "conduit <subcommand>"}. A player is not offered what they may not run.
   */
  public static byte[] mergeProxyCommands(ProtocolDefinition protocol, byte[] packet, List<String> serverNames,
      List<String> extraNames, java.util.Set<String> displaced, java.util.function.Predicate<String> shown) throws IOException {
    int id = PlayPackets.packetId(packet);
    int cursor = varIntLength(packet, 0);
    int count = readVarInt(packet, cursor);
    cursor += varIntLength(packet, cursor);
    if (count < 1) throw new IOException("command tree has no nodes");

    int rootIndexStart = lastVarIntStart(packet);
    if (readVarInt(packet, rootIndexStart) != 0) throw new IOException("command tree root is not node 0");

    int flags = packet[cursor] & 0xFF;
    if ((flags & 0x03) != 0) throw new IOException("command tree node 0 is not a root node");
    if ((flags & 0x10) != 0) throw new IOException("command tree root declares suggestions");
    int header = cursor + 1;
    int childCount = readVarInt(packet, header);
    header += varIntLength(packet, header);
    // Each child index takes at least a byte. Taken as the array size, a backend's -1 threw a
    // NegativeArraySizeException and its 2^31-1 an OutOfMemoryError, past the catch for an unreadable tree.
    if (childCount < 0 || childCount > rootIndexStart - header) throw new IOException("command tree root claims " + childCount + " children");
    int[] children = new int[childCount];
    for (int index = 0; index < childCount; index++) {
      children[index] = readVarInt(packet, header);
      header += varIntLength(packet, header);
    }
    if ((flags & 0x08) != 0) header += varIntLength(packet, header);
    if (header > rootIndexStart) throw new IOException("command tree root node overruns the packet");

    StringParser parser = stringParser(protocol);
    FlatTree tree = flattenAll(proxyNodes(serverNames, extraNames, displaced, parser, shown));
    List<Flat> flat = tree.nodes();
    List<Integer> topLevel = tree.topLevel();

    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(packet.length + 256);
    try (java.io.DataOutputStream output = new java.io.DataOutputStream(bytes)) {
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, id);
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, count + flat.size());
      output.writeByte(flags);
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, childCount + topLevel.size());
      for (int child : children) gg.tame.conduit.protocol.MinecraftOutput.varInt(output, child);
      for (int position : topLevel) gg.tame.conduit.protocol.MinecraftOutput.varInt(output, count + position);
      // The other nodes, byte for byte. Their indices are unchanged, so their children still match.
      output.write(packet, header, rootIndexStart - header);
      writeFlat(output, flat, count, parser);
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, 0);
    }
    return bytes.toByteArray();
  }
  private static int readVarInt(byte[] data, int offset) throws IOException {
    int result = 0;
    for (int shift = 0; shift <= 28; shift += 7) {
      if (offset >= data.length) throw new IOException("truncated varint");
      int current = data[offset++] & 0xFF;
      result |= (current & 0x7F) << shift;
      if ((current & 0x80) == 0) return result;
    }
    throw new IOException("varint too long");
  }
  private static int varIntLength(byte[] data, int offset) throws IOException {
    for (int length = 1; length <= 5; length++) {
      if (offset + length - 1 >= data.length) throw new IOException("truncated varint");
      if ((data[offset + length - 1] & 0x80) == 0) return length;
    }
    throw new IOException("varint too long");
  }
  /** Start offset of the final varint, found by walking back over continuation bytes. */
  /**
   * Where the trailing root index begins.
   *
   * <p>This used to be found by walking backwards from the end over every byte with the high bit
   * set, on the assumption that such a byte could only be a continuation of the root index. It
   * cannot: the bytes before the root index are the last node's, and a node ends in a high byte
   * whenever it carries a non-ASCII name or a properties payload ending in a raw float, double or
   * long -- which a real server's tree does and a hand-built test tree does not. The walk then
   * started the index a byte or more too early, and since {@code 0x80 0x00} decodes to 0 just as
   * {@code 0x00} does, the "root is node 0" check below waved it through. The merged packet lost
   * the end of that node, and the client dropped the connection failing to decode the tree.
   *
   * <p>There is no walking to do. A tree this merge will touch has root index 0, checked below,
   * and every implementation writes it as the single byte {@code 0x00}: the root index is the last
   * byte, and a packet whose last byte is anything else is not one to merge into.
   */
  private static int lastVarIntStart(byte[] data) throws IOException {
    int end = data.length - 1;
    if (end < 0 || data[end] != 0) throw new IOException("command tree does not end with root index 0");
    return end;
  }
  public static byte[] proxyOnly(ProtocolDefinition protocol, List<String> serverNames) throws IOException {
    return proxyOnly(protocol, serverNames, List.of());
  }
  public static byte[] proxyOnly(ProtocolDefinition protocol, List<String> serverNames, List<String> extraNames) throws IOException {
    return proxyOnly(protocol, serverNames, extraNames, java.util.Set.of());
  }
  public static byte[] proxyOnly(ProtocolDefinition protocol, List<String> serverNames, List<String> extraNames,
      java.util.Set<String> displaced) throws IOException {
    // Built the same way as the merge, rather than through CommandGraph: one writer means the tree a
    // client gets when there is no backend tree to merge into cannot drift from the merged one.
    return mergeProxyCommands(protocol,
        rootOnly(protocol.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DECLARE_COMMANDS)),
        serverNames, extraNames, displaced);
  }
  /**
   * A command node Conduit adds to the tree a client parses against.
   *
   * <p>One level of literals was all this used to be, and it is why {@code /conduit drain } and
   * {@code /gkick } suggested nothing: {@code drain} was a childless leaf, and {@code gkick} had no
   * argument node at all, so a 1.13+ client had nothing to complete and nothing to ask about.
   */
  public sealed interface ProxyNode {
    String name();

    List<ProxyNode> children();

    /** A fixed word, such as a subcommand or a configured server name. */
    record Literal(String name, List<ProxyNode> children) implements ProxyNode {
      public Literal(String name) {
        this(name, List.of());
      }

      /** Convenience for a literal whose children are all plain literals. */
      static Literal of(String name, List<String> childNames) {
        List<ProxyNode> children = new ArrayList<>(childNames.size());
        for (String child : childNames) children.add(new Literal(child));
        return new Literal(name, children);
      }
    }

    /**
     * A {@code brigadier:string} argument. {@code askServer} sets Brigadier's
     * {@code minecraft:ask_server} suggestion type, which is what makes the client send a
     * tab-complete request rather than guessing locally. It is the only way to offer names that
     * change while a client is connected: the tree is sent on join, on a server switch, and when
     * Conduit declares it again for a command or permission change, but never per keystroke.
     */
    record Argument(String name, boolean greedy, boolean askServer, List<ProxyNode> children) implements ProxyNode {
      public Argument(String name, boolean greedy, boolean askServer) {
        this(name, greedy, askServer, List.of());
      }
    }
  }

  /**
   * How {@code brigadier:string} is named in an argument node, which is not the same shape in every
   * release. Before 1.19 the parser is an identifier string; from 1.19 it is an index into the
   * registry vanilla builds, and 26.2 removed {@code brigadier:float} from that registry, moving
   * every later index down by one. Getting this wrong shifts every byte after the node, so a version
   * Conduit has no answer for is given plain literals and no argument node at all.
   */
  private record StringParser(ParserIds parsers) {
    void write(java.io.DataOutputStream output, boolean greedy) throws IOException {
      if (parsers.indexed()) gg.tame.conduit.protocol.MinecraftOutput.varInt(output, parsers.stringId());
      else gg.tame.conduit.protocol.MinecraftOutput.string(output, "brigadier:string");
      // brigadier:string's properties are one varint: 0 single word, 1 quotable, 2 greedy.
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, greedy ? 2 : 0);
    }
  }

  private static StringParser stringParser(ProtocolDefinition protocol) {
    // No command tree means no argument node either: 1.8 and 1.12 clients never parse one, and ask
    // the server about every command anyway, which is the completion path they already had.
    if (protocol == null || !protocol.capabilities().commandTree()) return null;
    return new StringParser(ParserIds.forProtocol(protocol.version().number()));
  }

  /** A node flattened to its wire fields, with child positions in this same list. */
  private record Flat(int flags, String name, boolean greedy, int[] children) {}

  /**
   * Depth-first, each node taking the next free position. A parent is reserved before its children
   * are appended so its own position stays below theirs, which keeps the written order and the
   * indices in step.
   */
  private static int flatten(List<Flat> out, java.util.IdentityHashMap<ProxyNode, Integer> shared, ProxyNode node) {
    // A command tree is a graph of indices, not a forest, so two parents may name the same child --
    // which is what vanilla does. Sharing matters: server names hang off /server, /plist, /send and
    // both /conduit drain branches, and a copy per parent made the packet grow with the square of
    // the server count. Identity, not equality: only a node the caller deliberately reused is shared.
    Integer already = shared.get(node);
    if (already != null) return already;
    int self = out.size();
    out.add(null);
    shared.put(node, self);
    List<ProxyNode> kids = node.children();
    int[] children = new int[kids.size()];
    for (int index = 0; index < kids.size(); index++) children[index] = flatten(out, shared, kids.get(index));
    boolean argument = node instanceof ProxyNode.Argument;
    boolean askServer = node instanceof ProxyNode.Argument arg && arg.askServer();
    boolean greedy = node instanceof ProxyNode.Argument arg && arg.greedy();
    // 0x01 literal / 0x02 argument, 0x04 executable, 0x10 has a suggestion type.
    int flags = (argument ? 0x02 : 0x01) | 0x04 | (askServer ? 0x10 : 0x00);
    out.set(self, new Flat(flags, node.name(), greedy, children));
    return self;
  }

  /**
   * Every node of every top-level command, flattened into one list whose positions are its indices,
   * with the positions of the top-level nodes themselves -- what the root's child list gains.
   *
   * <p>The two are returned together because sharing means neither can be worked out from the other:
   * a top-level node's position depends on how much of the tree before it was already emitted.
   */
  private record FlatTree(List<Flat> nodes, List<Integer> topLevel) {}

  private static FlatTree flattenAll(List<ProxyNode> roots) {
    List<Flat> flat = new ArrayList<>();
    var shared = new java.util.IdentityHashMap<ProxyNode, Integer>();
    List<Integer> topLevel = new ArrayList<>(roots.size());
    for (ProxyNode root : roots) topLevel.add(flatten(flat, shared, root));
    return new FlatTree(flat, topLevel);
  }

  /**
   * Writes the flattened nodes, shifting every child index by {@code offset} -- the number of nodes
   * the tree already had, since these go on the end and none of the existing indices may move.
   */
  private static void writeFlat(java.io.DataOutputStream output, List<Flat> flat, int offset,
      StringParser parser) throws IOException {
    for (Flat node : flat) {
      output.writeByte(node.flags());
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, node.children().length);
      for (int child : node.children()) gg.tame.conduit.protocol.MinecraftOutput.varInt(output, child + offset);
      gg.tame.conduit.protocol.MinecraftOutput.string(output, node.name());
      if ((node.flags() & 0x02) != 0) parser.write(output, node.greedy());
      if ((node.flags() & 0x10) != 0) gg.tame.conduit.protocol.MinecraftOutput.string(output, "minecraft:ask_server");
    }
  }

  /**
   * The proxy's own commands as a tree.
   *
   * <p>Server names are literals: they come from the configuration and do not change while a client
   * is connected, so the client completes them with no round trip. Player names cannot be, so they
   * are an {@code ask_server} argument and the client asks Conduit for them each time.
   */
  private static List<ProxyNode> proxyNodes(List<String> serverNames, List<String> extraNames,
      java.util.Set<String> displaced, StringParser parser, java.util.function.Predicate<String> shown) {
    boolean arguments = parser != null;
    List<ProxyNode> servers = new ArrayList<>();
    for (String name : serverNames) servers.add(new ProxyNode.Literal(name));

    // /conduit: the subcommands that take an argument get one, so /conduit drain <TAB> finally lists
    // the servers instead of nothing. The rest stay leaves.
    List<ProxyNode> conduitChildren = new ArrayList<>();
    for (String subcommand : CoreCommands.CONDUIT_SUBCOMMANDS) {
      if (!shown.test("conduit " + subcommand)) continue;
      switch (subcommand) {
        case "drain", "undrain" -> conduitChildren.add(new ProxyNode.Literal(subcommand, servers));
        case "maintenance", "attack" -> conduitChildren.add(ProxyNode.Literal.of(subcommand, List.of("on", "off", "status")));
        // invalidate's own argument is the IP a connection came from, so it stops here.
        case "cache" -> conduitChildren.add(ProxyNode.Literal.of(subcommand, List.of("invalidate")));
        default -> conduitChildren.add(new ProxyNode.Literal(subcommand));
      }
    }

    // /send <player|current|server> <server>: the first argument may be one of the literals the
    // completer offers or any online name, so a literal branch and an ask_server branch both hang
    // off it, each carrying the server names the second argument takes.
    List<ProxyNode> sendFirst = new ArrayList<>();
    sendFirst.add(new ProxyNode.Literal("current", servers));
    for (String name : serverNames) sendFirst.add(new ProxyNode.Literal(name, servers));
    if (arguments) sendFirst.add(new ProxyNode.Argument("player", false, true, servers));

    List<ProxyNode> literals = new ArrayList<>();
    literals.add(new ProxyNode.Literal("conduit", conduitChildren));
    literals.add(new ProxyNode.Literal("glist"));
    literals.add(new ProxyNode.Literal("plist", servers));
    literals.add(new ProxyNode.Literal("find", player(arguments)));
    literals.add(new ProxyNode.Literal("alert",
        arguments ? List.of(new ProxyNode.Argument("message", true, false)) : List.of()));
    literals.add(new ProxyNode.Literal("ping"));
    literals.add(new ProxyNode.Literal("hub"));
    literals.add(new ProxyNode.Literal("gkick", player(arguments)));
    literals.add(new ProxyNode.Literal("server", servers));
    literals.add(new ProxyNode.Literal("send", sendFirst));
    // One node, shared by every command below whose shape Conduit does not know -- see rawArguments.
    List<ProxyNode> rawArguments = arguments ? List.of(new ProxyNode.Argument("arguments", true, true)) : List.of();
    // A built-in a plugin displaced is declared as that plugin's commands are: the built-in's own
    // children would have the client suggest arguments the plugin never takes.
    literals.replaceAll(literal -> displaced.contains(literal.name())
        ? new ProxyNode.Literal(literal.name(), rawArguments) : literal);
    // Every name is marked emitted, shown or not, so a hidden built-in is not declared again below as
    // a bare literal from the registered names.
    java.util.LinkedHashSet<String> emitted = new java.util.LinkedHashSet<>();
    for (ProxyNode literal : literals) emitted.add(literal.name());
    literals.removeIf(literal -> !shown.test(literal.name()));
    // /<server> shortcuts first, then whatever else is registered -- plugin commands and their
    // aliases. A name the built-ins already own is theirs: a second literal for it would give the
    // root two children of the same name and the client would parse against the childless one.
    for (String name : serverNames) addLiteral(literals, emitted, name, shown, List.of());
    for (String name : extraNames) addLiteral(literals, emitted, name, shown, rawArguments);
    return List.copyOf(literals);
  }

  /** An ask_server player argument, or nothing where argument nodes cannot be written. */
  private static List<ProxyNode> player(boolean arguments) {
    return arguments ? List.of(new ProxyNode.Argument("player", false, true)) : List.of();
  }

  /**
   * A command Conduit knows only by name: the literal, plus {@code children}.
   *
   * <p>For a plugin's command those children are one greedy {@code ask_server} string, which is what
   * Velocity declares for a SimpleCommand or a RawCommand, and it is the whole of this bug. A bare
   * literal is a command that takes nothing: a 1.13+ client parses {@code /lpv user Kyle info}
   * against it, finds {@code lpv} matched and twelve characters left it has no node for, and paints
   * the line red -- while Conduit, which parses the line itself, runs it perfectly. The greedy
   * string soaks up the rest of the line, so the client sees a complete parse, and {@code ask_server}
   * sends the completion request that a childless literal never triggered either.
   *
   * <p>A server-name shortcut passes nothing: {@code /lobby} really does take no arguments.
   */
  private static void addLiteral(List<ProxyNode> literals, java.util.Set<String> emitted, String name,
      java.util.function.Predicate<String> shown, List<ProxyNode> children) {
    if (name == null) return;
    String key = name.toLowerCase(java.util.Locale.ROOT);
    if (key.isBlank() || !emitted.add(key) || !shown.test(key)) return;
    literals.add(new ProxyNode.Literal(key, children));
  }

  /** A Declare Commands packet holding nothing but an empty root, for the merge to append to. */
  private static byte[] rootOnly(int packetId) throws IOException {
    var bytes = new java.io.ByteArrayOutputStream();
    try (var output = new java.io.DataOutputStream(bytes)) {
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, packetId);
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, 1);
      output.writeByte(0);
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, 0);
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, 0);
    }
    return bytes.toByteArray();
  }
}
