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
    int[] children = new int[childCount];
    for (int index = 0; index < childCount; index++) {
      children[index] = readVarInt(packet, header);
      header += varIntLength(packet, header);
    }
    if ((flags & 0x08) != 0) header += varIntLength(packet, header);
    if (header > rootIndexStart) throw new IOException("command tree root node overruns the packet");

    List<CommandGraph.LiteralCommand> literals = proxyLiterals(serverNames, extraNames);
    int added = 0;
    for (CommandGraph.LiteralCommand literal : literals) added += 1 + literal.children().size();

    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(packet.length + 256);
    try (java.io.DataOutputStream output = new java.io.DataOutputStream(bytes)) {
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, id);
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, count + added);
      output.writeByte(flags);
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, childCount + literals.size());
      for (int child : children) gg.tame.conduit.protocol.MinecraftOutput.varInt(output, child);
      int next = count;
      for (CommandGraph.LiteralCommand literal : literals) {
        gg.tame.conduit.protocol.MinecraftOutput.varInt(output, next);
        next += 1 + literal.children().size();
      }
      // The other nodes, byte for byte. Their indices are unchanged, so their children still match.
      output.write(packet, header, rootIndexStart - header);
      next = count;
      for (CommandGraph.LiteralCommand literal : literals) {
        int firstChild = next + 1;
        writeLiteral(output, literal.name(), literal.children().size(), firstChild);
        for (String child : literal.children()) writeLiteral(output, child, 0, 0);
        next = firstChild + literal.children().size();
      }
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, 0);
    }
    return bytes.toByteArray();
  }
  /** Executable literal node: flags 0x01 (literal) | 0x04 (executable), then children, then name. */
  private static void writeLiteral(java.io.DataOutputStream output, String name, int childCount, int firstChild) throws IOException {
    output.writeByte(0x01 | 0x04);
    gg.tame.conduit.protocol.MinecraftOutput.varInt(output, childCount);
    for (int index = 0; index < childCount; index++) gg.tame.conduit.protocol.MinecraftOutput.varInt(output, firstChild + index);
    gg.tame.conduit.protocol.MinecraftOutput.string(output, name);
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
  private static int lastVarIntStart(byte[] data) throws IOException {
    int end = data.length - 1;
    if (end < 0 || (data[end] & 0x80) != 0) throw new IOException("command tree does not end with a varint");
    int start = end;
    while (start > 0 && (data[start - 1] & 0x80) != 0) start--;
    if (end - start >= 5) throw new IOException("trailing varint too long");
    return start;
  }
  public static byte[] proxyOnly(ProtocolDefinition protocol, List<String> serverNames) throws IOException {
    return proxyOnly(protocol, serverNames, List.of());
  }
  public static byte[] proxyOnly(ProtocolDefinition protocol, List<String> serverNames, List<String> extraNames) throws IOException {
    CommandGraph graph = CommandGraph.decode(rootOnly());
    graph.addLiteralCommands(proxyLiterals(serverNames, extraNames));
    return graph.encode(protocol.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DECLARE_COMMANDS));
  }
  private static List<CommandGraph.LiteralCommand> proxyLiterals(List<String> serverNames, List<String> extraNames) {
    List<String> sendChildren = new ArrayList<>();
    sendChildren.add("current");
    sendChildren.addAll(serverNames);
    List<CommandGraph.LiteralCommand> literals = new ArrayList<>();
    literals.add(new CommandGraph.LiteralCommand("conduit", CoreCommands.CONDUIT_SUBCOMMANDS));
    literals.add(new CommandGraph.LiteralCommand("glist", List.of()));
    literals.add(new CommandGraph.LiteralCommand("plist", serverNames));
    literals.add(new CommandGraph.LiteralCommand("find", List.of()));
    literals.add(new CommandGraph.LiteralCommand("alert", List.of()));
    literals.add(new CommandGraph.LiteralCommand("ping", List.of()));
    literals.add(new CommandGraph.LiteralCommand("hub", List.of()));
    literals.add(new CommandGraph.LiteralCommand("gkick", List.of()));
    literals.add(new CommandGraph.LiteralCommand("server", serverNames));
    literals.add(new CommandGraph.LiteralCommand("send", sendChildren));
    java.util.LinkedHashSet<String> emitted = new java.util.LinkedHashSet<>();
    for (CommandGraph.LiteralCommand literal : literals) emitted.add(literal.name());
    // /<server> shortcuts first, then whatever else is registered -- plugin commands and their
    // aliases. A name the built-ins already own is theirs: a second literal for it would give the
    // root two children of the same name and the client would parse against the childless one.
    for (String name : serverNames) addLiteral(literals, emitted, name);
    for (String name : extraNames) addLiteral(literals, emitted, name);
    return List.copyOf(literals);
  }
  private static void addLiteral(List<CommandGraph.LiteralCommand> literals, java.util.Set<String> emitted, String name) {
    if (name == null) return;
    String key = name.toLowerCase(java.util.Locale.ROOT);
    if (key.isBlank() || !emitted.add(key)) return;
    literals.add(new CommandGraph.LiteralCommand(key, List.of()));
  }
  private static byte[] rootOnly() throws IOException {
    var bytes = new java.io.ByteArrayOutputStream();
    try (var output = new java.io.DataOutputStream(bytes)) {
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, 1);
      output.writeByte(0);
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, 0);
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, 0);
    }
    return bytes.toByteArray();
  }
}
