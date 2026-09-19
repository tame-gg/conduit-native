// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.command;

import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Brigadier command graph.
 *
 * <p>An argument node names its parser differently in different releases and the packet does not say
 * which, so decoding one means being told: see {@link ParserIds}. {@link #decode(byte[])} assumes
 * 1.19 to 26.1, which is the numbering {@link ArgumentProperties} is written against.
 */
public final class CommandGraph {
  private final List<Node> nodes;
  private int root;
  public CommandGraph(List<Node> nodes, int root) { this.nodes = new ArrayList<>(nodes); this.root = root; }
  public List<Node> nodes() { return nodes; }
  public int root() { return root; }
  public static CommandGraph decode(byte[] body) throws IOException {
    return decode(body, ParserIds.INDEXED);
  }

  /** @param parsers how the release that wrote this tree names an argument node's parser */
  public static CommandGraph decode(byte[] body, ParserIds parsers) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
      int count = MinecraftInput.varInt(input);
      List<Node> nodes = new ArrayList<>(count);
      for (int index = 0; index < count; index++) nodes.add(Node.read(input, parsers));
      int root = MinecraftInput.varInt(input);
      if (root < 0 || root >= nodes.size()) throw new IOException("command graph root is out of range");
      for (Node node : nodes) node.validate(nodes.size());
      if (input.available() != 0) throw new IOException("trailing command graph bytes");
      return new CommandGraph(nodes, root);
    }
  }
  public byte[] encode(int packetId) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, packetId);
      MinecraftOutput.varInt(output, nodes.size());
      for (Node node : nodes) node.write(output);
      MinecraftOutput.varInt(output, root);
    }
    return bytes.toByteArray();
  }
  public void addLiteralCommands(List<LiteralCommand> commands) {
    Node rootNode = nodes.get(root);
    List<Integer> children = new ArrayList<>(rootNode.children);
    for (LiteralCommand command : commands) {
      int commandIndex = nodes.size();
      List<Integer> argumentChildren = new ArrayList<>();
      for (String child : command.children()) {
        int childIndex = nodes.size() + 1 + argumentChildren.size();
        argumentChildren.add(childIndex);
      }
      nodes.add(Node.literal(command.name(), true, argumentChildren));
      for (String child : command.children()) nodes.add(Node.literal(child, true, List.of()));
      children.add(commandIndex);
    }
    nodes.set(root, new Node(rootNode.flags, children, rootNode.redirect, rootNode.name, rootNode.parserId, rootNode.properties, rootNode.suggestions));
  }
  public record LiteralCommand(String name, List<String> children) {}
  public static final class Node {
    private final int flags;
    private final List<Integer> children;
    private final Integer redirect;
    private final String name;
    private final Integer parserId;
    /** Set instead of {@link #parserId} when the node came from a pre-1.19 tree. */
    private final String parserName;
    private final byte[] properties;
    private final String suggestions;
    private Node(int flags, List<Integer> children, Integer redirect, String name, Integer parserId, byte[] properties, String suggestions) {
      this(flags, children, redirect, name, parserId, null, properties, suggestions);
    }
    private Node(int flags, List<Integer> children, Integer redirect, String name, Integer parserId, String parserName,
                 byte[] properties, String suggestions) {
      this.flags = flags; this.children = List.copyOf(children); this.redirect = redirect; this.name = name;
      this.parserId = parserId; this.parserName = parserName;
      this.properties = properties == null ? new byte[0] : properties; this.suggestions = suggestions;
    }
    static Node literal(String name, boolean executable, List<Integer> children) {
      int flags = 1 | (executable ? 0x04 : 0);
      return new Node(flags, children, null, name, null, new byte[0], null);
    }
    static Node read(DataInputStream input) throws IOException {
      return read(input, ParserIds.INDEXED);
    }
    static Node read(DataInputStream input, ParserIds parsers) throws IOException {
      int flags = input.readUnsignedByte();
      int type = flags & 0x03;
      int childCount = MinecraftInput.varInt(input);
      List<Integer> children = new ArrayList<>(childCount);
      for (int index = 0; index < childCount; index++) children.add(MinecraftInput.varInt(input));
      Integer redirect = (flags & 0x08) != 0 ? MinecraftInput.varInt(input) : null;
      String name = (type == 1 || type == 2) ? MinecraftInput.string(input, 32767) : null;
      // The identifier form carries no id, so properties are keyed by the name instead. Either way
      // the id handed to ArgumentProperties is the canonical one: a release with its own numbering
      // would otherwise have its property payloads read at the wrong length.
      String parserName = type == 2 && !parsers.indexed() ? MinecraftInput.string(input, 32767) : null;
      Integer parserId = type == 2 && parsers.indexed() ? MinecraftInput.varInt(input) : null;
      byte[] properties = type != 2 ? new byte[0]
          : ArgumentProperties.read(input, parserId != null
              ? parsers.canonical(parserId)
              : ArgumentProperties.idFor(parserName));
      String suggestions = (flags & 0x10) != 0 ? MinecraftInput.string(input, 32767) : null;
      return new Node(flags, children, redirect, name, parserId, parserName, properties, suggestions);
    }
    void write(DataOutputStream output) throws IOException {
      output.writeByte(flags);
      MinecraftOutput.varInt(output, children.size());
      for (int child : children) MinecraftOutput.varInt(output, child);
      if ((flags & 0x08) != 0) MinecraftOutput.varInt(output, redirect);
      int type = flags & 0x03;
      if (type == 1 || type == 2) MinecraftOutput.string(output, name);
      if (type == 2) {
        if (parserId != null) MinecraftOutput.varInt(output, parserId);
        else MinecraftOutput.string(output, parserName);
        output.write(properties);
      }
      if ((flags & 0x10) != 0) MinecraftOutput.string(output, suggestions);
    }
    void validate(int size) throws IOException {
      for (int child : children) if (child < 0 || child >= size) throw new IOException("command child index " + child + " out of bounds for length " + size);
      if (redirect != null && (redirect < 0 || redirect >= size)) throw new IOException("command redirect index out of bounds");
    }
  }
}
