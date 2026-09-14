package gg.tame.conduit.protocol.semantic;

import java.util.ArrayList;
import java.util.List;

/**
 * Command tree independent of per-protocol declare-commands packet layout.
 * Conduit commands (/server, /conduit, /send) remain usable across supported clients.
 */
public final class SemanticCommandNode {
  private final String name;
  private final NodeType type;
  private final boolean executable;
  private final List<SemanticCommandNode> children = new ArrayList<>();

  public SemanticCommandNode(String name, NodeType type, boolean executable) {
    this.name = name == null ? "" : name;
    this.type = type == null ? NodeType.LITERAL : type;
    this.executable = executable;
  }

  public String name() { return name; }
  public NodeType type() { return type; }
  public boolean executable() { return executable; }
  public List<SemanticCommandNode> children() { return List.copyOf(children); }

  public SemanticCommandNode addChild(SemanticCommandNode child) {
    children.add(child);
    return this;
  }

  public enum NodeType { ROOT, LITERAL, ARGUMENT }
}
