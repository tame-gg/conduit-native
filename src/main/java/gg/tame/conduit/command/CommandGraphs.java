// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.command;

import gg.tame.conduit.api.command.CommandSyntax;
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
   * The most nodes one command may declare. Vanilla's whole tree is a few thousand across every
   * command it has, so a single command past this is a tree built from data rather than written
   * out, and the client pays for all of it on every join and every server switch.
   */
  static final int MAX_DECLARED_NODES = 512;
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
   * As above, with {@code syntax} what each registered name declares follows it
   * (CommandManager#syntaxOf) -- a Velocity BrigadierCommand's own node tree, say. A name that
   * declares nothing keeps the one greedy argument every command used to get.
   */
  public static byte[] mergeProxyCommands(ProtocolDefinition protocol, byte[] packet, List<String> serverNames,
      List<String> extraNames, java.util.Set<String> displaced, java.util.function.Predicate<String> shown,
      java.util.function.Function<String, List<CommandSyntax>> syntax) throws IOException {
    return merge(protocol, packet, serverNames, extraNames, displaced, shown, syntax);
  }
  /**
   * As above, declaring only the commands {@code shown} accepts (CommandManager#shownTo): a top-level
   * name, or {@code "conduit <subcommand>"}. A player is not offered what they may not run.
   */
  public static byte[] mergeProxyCommands(ProtocolDefinition protocol, byte[] packet, List<String> serverNames,
      List<String> extraNames, java.util.Set<String> displaced, java.util.function.Predicate<String> shown) throws IOException {
    return merge(protocol, packet, serverNames, extraNames, displaced, shown, name -> List.of());
  }

  private static byte[] merge(ProtocolDefinition protocol, byte[] packet, List<String> serverNames,
      List<String> extraNames, java.util.Set<String> displaced, java.util.function.Predicate<String> shown,
      java.util.function.Function<String, List<CommandSyntax>> syntax) throws IOException {
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

    ParserWriter parser = parserWriter(protocol);
    FlatTree tree = flattenAll(proxyNodes(serverNames, extraNames, displaced, parser, shown, syntax));
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
  /** A literal whose children are all plain literals. */
  private static CommandSyntax.Literal literal(String name, List<String> childNames) {
    List<CommandSyntax> children = new ArrayList<>(childNames.size());
    for (String child : childNames) children.add(new CommandSyntax.Literal(child));
    return new CommandSyntax.Literal(name, children);
  }

  /**
   * Writes an argument node's parser, which is not the same shape in every release. Before 1.19 the
   * parser is an identifier string; from 1.19 it is an index into the registry vanilla builds, and a
   * release dropping {@code brigadier:float} from that registry would move every later index down by
   * one -- see {@link ParserIds}. Getting this wrong shifts every byte after the node, so a version
   * Conduit has no answer for is given plain literals and no argument node at all.
   *
   * <p>Only Brigadier's own six parsers are written. They are the ones every client back to 1.13
   * has, and the only ones a plugin can name without the game's own classes.
   */
  private record ParserWriter(ParserIds parsers) {
    void write(java.io.DataOutputStream output, CommandSyntax.Parser parser) throws IOException {
      switch (parser) {
        case CommandSyntax.Parser.Bool ignored -> id(output, 0, "brigadier:bool");
        case CommandSyntax.Parser.Phrase phrase -> {
          id(output, 5, "brigadier:string");
          // One varint: 0 single word, 1 quotable, 2 greedy -- the order Width declares them in.
          gg.tame.conduit.protocol.MinecraftOutput.varInt(output, phrase.width().ordinal());
        }
        case CommandSyntax.Parser.Range range -> {
          id(output, canonical(range.kind()), identifier(range.kind()));
          // A flags byte saying which bounds follow, then each at the parser's own width.
          output.writeByte((range.min() != null ? 0x01 : 0) | (range.max() != null ? 0x02 : 0));
          for (Number bound : new Number[] {range.min(), range.max()}) {
            if (bound == null) continue;
            switch (range.kind()) {
              case FLOAT -> output.writeFloat(bound.floatValue());
              case DOUBLE -> output.writeDouble(bound.doubleValue());
              case INTEGER -> output.writeInt(bound.intValue());
              case LONG -> output.writeLong(bound.longValue());
            }
          }
        }
      }
    }
    private static int canonical(CommandSyntax.Parser.Range.Kind kind) {
      return switch (kind) {
        case FLOAT -> 1;
        case DOUBLE -> 2;
        case INTEGER -> 3;
        case LONG -> 4;
      };
    }
    private static String identifier(CommandSyntax.Parser.Range.Kind kind) {
      return switch (kind) {
        case FLOAT -> "brigadier:float";
        case DOUBLE -> "brigadier:double";
        case INTEGER -> "brigadier:integer";
        case LONG -> "brigadier:long";
      };
    }
    private void id(java.io.DataOutputStream output, int canonical, String identifier) throws IOException {
      if (parsers.indexed()) gg.tame.conduit.protocol.MinecraftOutput.varInt(output, parsers.wireId(canonical));
      else gg.tame.conduit.protocol.MinecraftOutput.string(output, identifier);
    }
  }

  private static ParserWriter parserWriter(ProtocolDefinition protocol) {
    // No command tree means no argument node either: 1.8 and 1.12 clients never parse one, and ask
    // the server about every command anyway, which is the completion path they already had.
    if (protocol == null || !protocol.capabilities().commandTree()) return null;
    return new ParserWriter(ParserIds.forProtocol(protocol.version().number()));
  }

  /** A node flattened to its wire fields, with child positions in this same list. */
  private record Flat(int flags, String name, CommandSyntax.Parser parser, int[] children) {}

  /**
   * Depth-first, each node taking the next free position. A parent is reserved before its children
   * are appended so its own position stays below theirs, which keeps the written order and the
   * indices in step.
   */
  private static int flatten(List<Flat> out, java.util.IdentityHashMap<CommandSyntax, Integer> shared, CommandSyntax node) {
    // A command tree is a graph of indices, not a forest, so two parents may name the same child --
    // which is what vanilla does. Sharing matters: server names hang off /server, /plist, /send and
    // both /conduit drain branches, and a copy per parent made the packet grow with the square of
    // the server count. Identity, not equality: only a node the caller deliberately reused is shared.
    Integer already = shared.get(node);
    if (already != null) return already;
    int self = out.size();
    out.add(null);
    shared.put(node, self);
    List<CommandSyntax> kids = node.children();
    int[] children = new int[kids.size()];
    for (int index = 0; index < kids.size(); index++) children[index] = flatten(out, shared, kids.get(index));
    CommandSyntax.Parser parser = node instanceof CommandSyntax.Argument argument ? argument.parser() : null;
    boolean askServer = node instanceof CommandSyntax.Argument argument && argument.askServer();
    // 0x01 literal / 0x02 argument, 0x04 executable, 0x10 has a suggestion type.
    int flags = (parser != null ? 0x02 : 0x01) | 0x04 | (askServer ? 0x10 : 0x00);
    out.set(self, new Flat(flags, node.name(), parser, children));
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

  private static FlatTree flattenAll(List<CommandSyntax> roots) {
    List<Flat> flat = new ArrayList<>();
    var shared = new java.util.IdentityHashMap<CommandSyntax, Integer>();
    List<Integer> topLevel = new ArrayList<>(roots.size());
    for (CommandSyntax root : roots) topLevel.add(flatten(flat, shared, root));
    return new FlatTree(flat, topLevel);
  }

  /**
   * Writes the flattened nodes, shifting every child index by {@code offset} -- the number of nodes
   * the tree already had, since these go on the end and none of the existing indices may move.
   */
  private static void writeFlat(java.io.DataOutputStream output, List<Flat> flat, int offset,
      ParserWriter parser) throws IOException {
    for (Flat node : flat) {
      output.writeByte(node.flags());
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, node.children().length);
      for (int child : node.children()) gg.tame.conduit.protocol.MinecraftOutput.varInt(output, child + offset);
      gg.tame.conduit.protocol.MinecraftOutput.string(output, node.name());
      if ((node.flags() & 0x02) != 0) parser.write(output, node.parser());
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
  private static List<CommandSyntax> proxyNodes(List<String> serverNames, List<String> extraNames,
      java.util.Set<String> displaced, ParserWriter parser, java.util.function.Predicate<String> shown,
      java.util.function.Function<String, List<CommandSyntax>> syntax) {
    boolean arguments = parser != null;
    List<CommandSyntax> servers = new ArrayList<>();
    for (String name : serverNames) servers.add(new CommandSyntax.Literal(name));

    // /conduit: the subcommands that take an argument get one, so /conduit drain <TAB> finally lists
    // the servers instead of nothing. The rest stay leaves.
    List<CommandSyntax> conduitChildren = new ArrayList<>();
    for (String subcommand : CoreCommands.CONDUIT_SUBCOMMANDS) {
      if (!shown.test("conduit " + subcommand)) continue;
      switch (subcommand) {
        case "drain", "undrain" -> conduitChildren.add(new CommandSyntax.Literal(subcommand, servers));
        case "maintenance", "attack" -> conduitChildren.add(literal(subcommand, List.of("on", "off", "status")));
        // invalidate's own argument is the IP a connection came from, so it stops here.
        case "cache" -> conduitChildren.add(literal(subcommand, List.of("invalidate")));
        default -> conduitChildren.add(new CommandSyntax.Literal(subcommand));
      }
    }

    // /send <player|current|server> <server>: the first argument may be one of the literals the
    // completer offers or any online name, so a literal branch and an ask_server branch both hang
    // off it, each carrying the server names the second argument takes.
    List<CommandSyntax> sendFirst = new ArrayList<>();
    sendFirst.add(new CommandSyntax.Literal("current", servers));
    for (String name : serverNames) sendFirst.add(new CommandSyntax.Literal(name, servers));
    if (arguments) sendFirst.add(new CommandSyntax.Argument("player", CommandSyntax.Parser.word(), true, servers));

    List<CommandSyntax> literals = new ArrayList<>();
    literals.add(new CommandSyntax.Literal("conduit", conduitChildren));
    literals.add(new CommandSyntax.Literal("glist"));
    literals.add(new CommandSyntax.Literal("plist", servers));
    literals.add(new CommandSyntax.Literal("find", player(arguments)));
    literals.add(new CommandSyntax.Literal("alert",
        arguments ? List.of(new CommandSyntax.Argument("message", CommandSyntax.Parser.greedy(), false)) : List.of()));
    literals.add(new CommandSyntax.Literal("ping"));
    literals.add(new CommandSyntax.Literal("hub"));
    literals.add(new CommandSyntax.Literal("gkick", player(arguments)));
    literals.add(new CommandSyntax.Literal("server", servers));
    literals.add(new CommandSyntax.Literal("send", sendFirst));
    // One node, shared by every command below whose shape Conduit is not told -- see addLiteral.
    List<CommandSyntax> rawArguments = arguments
        ? List.of(new CommandSyntax.Argument("arguments", CommandSyntax.Parser.greedy(), true)) : List.of();
    // A built-in a plugin displaced is declared as that plugin's commands are: the built-in's own
    // children would have the client suggest arguments the plugin never takes.
    literals.replaceAll(node -> displaced.contains(node.name())
        ? new CommandSyntax.Literal(node.name(), declared(syntax, node.name(), rawArguments, arguments)) : node);
    // Every name is marked emitted, shown or not, so a hidden built-in is not declared again below as
    // a bare literal from the registered names.
    java.util.LinkedHashSet<String> emitted = new java.util.LinkedHashSet<>();
    for (CommandSyntax node : literals) emitted.add(node.name());
    literals.removeIf(node -> !shown.test(node.name()));
    // /<server> shortcuts first, then whatever else is registered -- plugin commands and their
    // aliases. A name the built-ins already own is theirs: a second literal for it would give the
    // root two children of the same name and the client would parse against the childless one.
    for (String name : serverNames) addLiteral(literals, emitted, name, shown, List.of());
    for (String name : extraNames) {
      addLiteral(literals, emitted, name, shown, declared(syntax, name, rawArguments, arguments));
    }
    return List.copyOf(literals);
  }

  /**
   * What goes under a registered command's literal: the shape it declared, or the one greedy
   * argument a command that declared nothing gets.
   *
   * <p>A declared tree is capped. It goes on the wire in a packet a client must decode in full, and
   * a plugin that builds its tree from a list -- every warp, every region -- can produce one large
   * enough to disconnect everyone on the proxy. Past the cap the command falls back to the greedy
   * argument, which is correct, if less helpful, at any size.
   */
  private static List<CommandSyntax> declared(java.util.function.Function<String, List<CommandSyntax>> syntax,
      String name, List<CommandSyntax> rawArguments, boolean arguments) {
    if (!arguments) return List.of();
    List<CommandSyntax> nodes = syntax.apply(name);
    if (nodes == null || nodes.isEmpty()) return rawArguments;
    int size = size(nodes, 0);
    if (size <= MAX_DECLARED_NODES) return nodes;
    gg.tame.conduit.log.ConduitLog.warn("The command tree /" + name + " declares is " + size + " nodes, past the "
        + MAX_DECLARED_NODES + " Conduit will send; clients are told it takes free text instead");
    return rawArguments;
  }

  /**
   * How many nodes a declared tree is, giving up once it is past the cap: counting the whole of a
   * tree built to be enormous is the cost the cap exists to avoid.
   */
  private static int size(List<CommandSyntax> nodes, int sofar) {
    int total = sofar;
    for (CommandSyntax node : nodes) {
      if (total > MAX_DECLARED_NODES) return total;
      total = size(node.children(), total + 1);
    }
    return total;
  }

  /** An ask_server player argument, or nothing where argument nodes cannot be written. */
  private static List<CommandSyntax> player(boolean arguments) {
    return arguments ? List.of(new CommandSyntax.Argument("player", CommandSyntax.Parser.word(), true)) : List.of();
  }

  /**
   * A registered command: the literal, plus {@code children} -- the shape it declared, or the one
   * greedy {@code ask_server} string a command that declared none is given.
   *
   * <p>That greedy string is what Velocity declares for a SimpleCommand or a RawCommand, and it is
   * what a bare literal should always have been. A bare literal is a command that takes nothing: a
   * 1.13+ client parses {@code /lpv user Kyle info} against it, finds {@code lpv} matched and twelve
   * characters left it has no node for, and paints the line red -- while Conduit, which parses the
   * line itself, runs it perfectly. The greedy string soaks up the rest of the line, so the client
   * sees a complete parse, and {@code ask_server} sends the completion request that a childless
   * literal never triggered either.
   *
   * <p>A server-name shortcut passes nothing: {@code /lobby} really does take no arguments.
   */
  private static void addLiteral(List<CommandSyntax> literals, java.util.Set<String> emitted, String name,
      java.util.function.Predicate<String> shown, List<CommandSyntax> children) {
    if (name == null) return;
    String key = name.toLowerCase(java.util.Locale.ROOT);
    if (key.isBlank() || !emitted.add(key) || !shown.test(key)) return;
    literals.add(new CommandSyntax.Literal(key, children));
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
