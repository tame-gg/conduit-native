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

/** Brigadier command graph for 1.19+ numeric parser IDs (1.20.1 and 1.20.4). */
public final class CommandGraph {
  private final List<Node> nodes;
  private int root;
  public CommandGraph(List<Node> nodes, int root) { this.nodes = new ArrayList<>(nodes); this.root = root; }
  public List<Node> nodes() { return nodes; }
  public int root() { return root; }
  public static CommandGraph decode(byte[] body) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
      int count = MinecraftInput.varInt(input);
      List<Node> nodes = new ArrayList<>(count);
      for (int index = 0; index < count; index++) nodes.add(Node.read(input));
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
    private final byte[] properties;
    private final String suggestions;
    private Node(int flags, List<Integer> children, Integer redirect, String name, Integer parserId, byte[] properties, String suggestions) {
      this.flags = flags; this.children = List.copyOf(children); this.redirect = redirect; this.name = name;
      this.parserId = parserId; this.properties = properties == null ? new byte[0] : properties; this.suggestions = suggestions;
    }
    static Node literal(String name, boolean executable, List<Integer> children) {
      int flags = 1 | (executable ? 0x04 : 0);
      return new Node(flags, children, null, name, null, new byte[0], null);
    }
    static Node read(DataInputStream input) throws IOException {
      int flags = input.readUnsignedByte();
      int type = flags & 0x03;
      int childCount = MinecraftInput.varInt(input);
      List<Integer> children = new ArrayList<>(childCount);
      for (int index = 0; index < childCount; index++) children.add(MinecraftInput.varInt(input));
      Integer redirect = (flags & 0x08) != 0 ? MinecraftInput.varInt(input) : null;
      String name = (type == 1 || type == 2) ? MinecraftInput.string(input, 32767) : null;
      Integer parserId = type == 2 ? MinecraftInput.varInt(input) : null;
      byte[] properties = type == 2 ? ArgumentProperties.read(input, parserId) : new byte[0];
      String suggestions = (flags & 0x10) != 0 ? MinecraftInput.string(input, 32767) : null;
      return new Node(flags, children, redirect, name, parserId, properties, suggestions);
    }
    void write(DataOutputStream output) throws IOException {
      output.writeByte(flags);
      MinecraftOutput.varInt(output, children.size());
      for (int child : children) MinecraftOutput.varInt(output, child);
      if ((flags & 0x08) != 0) MinecraftOutput.varInt(output, redirect);
      int type = flags & 0x03;
      if (type == 1 || type == 2) MinecraftOutput.string(output, name);
      if (type == 2) { MinecraftOutput.varInt(output, parserId); output.write(properties); }
      if ((flags & 0x10) != 0) MinecraftOutput.string(output, suggestions);
    }
    void validate(int size) throws IOException {
      for (int child : children) if (child < 0 || child >= size) throw new IOException("command child index " + child + " out of bounds for length " + size);
      if (redirect != null && (redirect < 0 || redirect >= size)) throw new IOException("command redirect index out of bounds");
    }
  }
}
