// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Native Conduit chat component. Independent of Adventure; Velocity adapters convert into this.
 */
public final class Text {
  private final String content;
  private final TextColor color;
  private final boolean bold;
  private final boolean italic;
  private final String clickCommand;
  private final Text hover;
  private final List<Text> children;

  private Text(String content, TextColor color, boolean bold, boolean italic, String clickCommand, Text hover, List<Text> children) {
    this.content = content == null ? "" : content;
    this.color = color;
    this.bold = bold;
    this.italic = italic;
    this.clickCommand = clickCommand;
    this.hover = hover;
    this.children = List.copyOf(children);
  }

  public static Text empty() { return of(""); }
  public static Text of(String content) {
    return new Text(content, null, false, false, null, null, List.of());
  }
  public static Text newline() { return of("\n"); }
  public static Text join(Text... parts) {
    Text root = empty();
    return root.append(parts);
  }

  public Text color(TextColor color) {
    return new Text(content, color, bold, italic, clickCommand, hover, children);
  }
  public Text bold() { return new Text(content, color, true, italic, clickCommand, hover, children); }
  public Text italic() { return new Text(content, color, bold, true, clickCommand, hover, children); }
  public Text clickRun(String command) {
    String value = command == null ? null : (command.startsWith("/") ? command : "/" + command);
    return new Text(content, color, bold, italic, value, hover, children);
  }
  public Text hover(Text hover) {
    return new Text(content, color, bold, italic, clickCommand, hover, children);
  }
  public Text hover(String hover) { return hover(of(hover)); }
  public Text append(Text... parts) {
    List<Text> next = new ArrayList<>(children);
    for (Text part : parts) {
      if (part != null) next.add(part);
    }
    return new Text(content, color, bold, italic, clickCommand, hover, next);
  }
  public Text append(String plain) { return append(of(plain)); }

  public String content() { return content; }
  public TextColor color() { return color; }
  public boolean isBold() { return bold; }
  public boolean isItalic() { return italic; }
  public String clickCommand() { return clickCommand; }
  public Text hover() { return hover; }
  public List<Text> children() { return children; }

  /** Flattened plain text for logs, consoles, and tests. */
  public String plain() {
    StringBuilder out = new StringBuilder(content);
    for (Text child : children) out.append(child.plain());
    return out.toString();
  }

  @Override public String toString() { return plain(); }
  @Override public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof Text text)) return false;
    return bold == text.bold
        && italic == text.italic
        && Objects.equals(content, text.content)
        && color == text.color
        && Objects.equals(clickCommand, text.clickCommand)
        && Objects.equals(hover, text.hover)
        && Objects.equals(children, text.children);
  }
  @Override public int hashCode() {
    return Objects.hash(content, color, bold, italic, clickCommand, hover, children);
  }
}
