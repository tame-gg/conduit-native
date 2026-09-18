// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.text;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Native Conduit chat component. Independent of Adventure; Velocity adapters convert into this.
 *
 * <p>A component is either literal text ({@link #of}) or a translation ({@link #translatable}) the
 * client looks up in its own language, and carries a style and children, which inherit its style.
 * Immutable: every method that changes something returns a new component.
 *
 * <p>Each client is sent what its release can show. Before 1.16 an RGB colour becomes the nearest
 * named one; a translation's fallback needs 1.19.4 and insertion 1.8; copy-to-clipboard clicks need
 * 1.15. What a client cannot show is left out rather than sent in a form it would reject.
 */
public final class Text {
  /** The five text decorations. Each is on, explicitly off, or unset, which inherits the parent's. */
  public enum Decoration {
    BOLD("bold"), ITALIC("italic"), UNDERLINED("underlined"), STRIKETHROUGH("strikethrough"), OBFUSCATED("obfuscated");

    private final String key;
    Decoration(String key) { this.key = key; }
    /** The component key, such as {@code "bold"}. */
    public String key() { return key; }
  }

  /** What clicking the text does. {@code value} is the URL, command, text or page number the action takes. */
  public record ClickEvent(Action action, String value) {
    public enum Action { OPEN_URL, RUN_COMMAND, SUGGEST_COMMAND, COPY_TO_CLIPBOARD, CHANGE_PAGE }

    public ClickEvent {
      Objects.requireNonNull(action, "action");
      Objects.requireNonNull(value, "value");
    }
    public static ClickEvent openUrl(String url) { return new ClickEvent(Action.OPEN_URL, url); }
    public static ClickEvent runCommand(String command) { return new ClickEvent(Action.RUN_COMMAND, command); }
    public static ClickEvent suggestCommand(String command) { return new ClickEvent(Action.SUGGEST_COMMAND, command); }
    public static ClickEvent copyToClipboard(String text) { return new ClickEvent(Action.COPY_TO_CLIPBOARD, text); }
    /** Turns to {@code page} of the book the text is in; only books act on it. */
    public static ClickEvent changePage(int page) { return new ClickEvent(Action.CHANGE_PAGE, Integer.toString(page)); }
  }

  private record Style(TextColor color, Map<Decoration, Boolean> decorations, ClickEvent click, Text hover, String insertion) {
    static final Style EMPTY = new Style(null, Map.of(), null, null, null);
    Style color(TextColor value) { return new Style(value, decorations, click, hover, insertion); }
    Style decoration(Decoration decoration, Boolean state) {
      Map<Decoration, Boolean> next = new EnumMap<>(Decoration.class);
      next.putAll(decorations);
      if (state == null) next.remove(decoration);
      else next.put(decoration, state);
      return new Style(color, Map.copyOf(next), click, hover, insertion);
    }
    Style click(ClickEvent value) { return new Style(color, decorations, value, hover, insertion); }
    Style hover(Text value) { return new Style(color, decorations, click, value, insertion); }
    Style insertion(String value) { return new Style(color, decorations, click, hover, value); }
  }

  private final String content;
  private final String translationKey;
  private final List<Text> arguments;
  private final String fallback;
  private final Style style;
  private final List<Text> children;

  private Text(String content, String translationKey, List<Text> arguments, String fallback, Style style, List<Text> children) {
    this.content = content == null ? "" : content;
    this.translationKey = translationKey;
    this.arguments = List.copyOf(arguments);
    this.fallback = fallback;
    this.style = style;
    this.children = List.copyOf(children);
  }

  public static Text empty() { return of(""); }
  public static Text of(String content) {
    return new Text(content, null, List.of(), null, Style.EMPTY, List.of());
  }
  public static Text newline() { return of("\n"); }
  public static Text join(Text... parts) {
    Text root = empty();
    return root.append(parts);
  }
  /** A translation the client renders in its own language, with {@code arguments} filling its {@code %s} slots. */
  public static Text translatable(String key, Text... arguments) {
    Objects.requireNonNull(key, "key");
    List<Text> filled = new ArrayList<>();
    for (Text argument : arguments) filled.add(argument == null ? empty() : argument);
    return new Text("", key, filled, null, Style.EMPTY, List.of());
  }

  private Text with(Style next) { return new Text(content, translationKey, arguments, fallback, next, children); }

  public Text color(TextColor color) { return with(style.color(color)); }
  /** Sets a decoration on ({@code true}), off ({@code false}), or back to inherited ({@code null}). */
  public Text decoration(Decoration decoration, Boolean state) { return with(style.decoration(decoration, state)); }
  public Text decorate(Decoration decoration) { return decoration(decoration, true); }
  public Text bold() { return decorate(Decoration.BOLD); }
  public Text italic() { return decorate(Decoration.ITALIC); }
  public Text click(ClickEvent click) { return with(style.click(click)); }
  /** Runs {@code command} when clicked; a leading slash is added if it has none. */
  public Text clickRun(String command) {
    return click(command == null ? null : ClickEvent.runCommand(command.startsWith("/") ? command : "/" + command));
  }
  public Text hover(Text hover) { return with(style.hover(hover)); }
  public Text hover(String hover) { return hover(of(hover)); }
  /** Text put into the player's chat box when they shift-click this. */
  public Text insertion(String insertion) { return with(style.insertion(insertion)); }
  /** What a translation shows when the client has no such key. Meaningless on literal text. */
  public Text fallback(String fallback) { return new Text(content, translationKey, arguments, fallback, style, children); }
  public Text append(Text... parts) {
    List<Text> next = new ArrayList<>(children);
    for (Text part : parts) {
      if (part != null) next.add(part);
    }
    return new Text(content, translationKey, arguments, fallback, style, next);
  }
  public Text append(String plain) { return append(of(plain)); }

  /** The literal text; empty for a translation. */
  public String content() { return content; }
  /** The translation key, or null for literal text. */
  public String translationKey() { return translationKey; }
  public List<Text> arguments() { return arguments; }
  /** A translation's fallback, or null. */
  public String fallback() { return fallback; }
  public TextColor color() { return style.color(); }
  /** {@code true} on, {@code false} explicitly off, {@code null} inherited. */
  public Boolean decoration(Decoration decoration) { return style.decorations().get(decoration); }
  public boolean isBold() { return Boolean.TRUE.equals(decoration(Decoration.BOLD)); }
  public boolean isItalic() { return Boolean.TRUE.equals(decoration(Decoration.ITALIC)); }
  public ClickEvent clickEvent() { return style.click(); }
  public Text hover() { return style.hover(); }
  public String insertion() { return style.insertion(); }
  public List<Text> children() { return children; }

  /**
   * Flattened plain text for logs, consoles, and tests. Conduit has no language files, so a
   * translation reads as its fallback, or else its key.
   */
  public String plain() {
    StringBuilder out = new StringBuilder(ownText());
    for (Text child : children) out.append(child.plain());
    return out.toString();
  }

  /** This component's own text without its children: the literal, or a translation's fallback or key. */
  public String ownText() {
    if (translationKey == null) return content;
    return fallback != null ? fallback : translationKey;
  }

  @Override public String toString() { return plain(); }
  @Override public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof Text text)) return false;
    return content.equals(text.content)
        && Objects.equals(translationKey, text.translationKey)
        && arguments.equals(text.arguments)
        && Objects.equals(fallback, text.fallback)
        && style.equals(text.style)
        && children.equals(text.children);
  }
  @Override public int hashCode() {
    return Objects.hash(content, translationKey, arguments, fallback, style, children);
  }
}
