// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.text;

import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.api.text.TextColor;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.NetworkNbt;
import gg.tame.conduit.protocol.ProtocolEras;
import gg.tame.conduit.protocol.text.ComponentCodec;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Native {@link Text} as the component a given client reads, and back.
 *
 * <p>Encoding builds one component tree for the client's release ({@link #tree}) and writes it as a
 * JSON string or, from 1.20.3 in Play and Configuration, as network NBT; the two carry the same
 * tree. What changes with the release is what the tree may hold, which {@link ProtocolEras} names:
 * RGB colours, the hover key, insertion, fallback, and 1.21.5's renamed and validated events.
 */
public final class TextCodec {
  private TextCodec() {}

  /** Writes {@code text} for {@code protocol}'s Play and Configuration: network NBT from 1.20.3, else a JSON string. */
  public static void write(DataOutput output, Text text, int protocol) throws IOException {
    write(output, text, protocol, ProtocolEras.textComponentNbt(protocol));
  }

  /** Writes {@code text} for {@code protocol} in the given form; Login's disconnect is JSON in every release. */
  public static void write(DataOutput output, Text text, int protocol, boolean nbt) throws IOException {
    Map<String, Object> tree = tree(text, protocol);
    if (nbt) ComponentCodec.writeNbt(output, tree);
    else MinecraftOutput.string(output, ComponentCodec.toJson(tree));
  }

  /** {@code text} as JSON for {@code protocol}. */
  public static String toJson(Text text, int protocol) {
    return ComponentCodec.toJson(tree(text, protocol));
  }

  /** {@code text} as the component tree {@code protocol} reads, without anything that release lacks. */
  public static Map<String, Object> tree(Text text, int protocol) {
    Map<String, Object> tree = new LinkedHashMap<>();
    if (text.translationKey() != null) {
      tree.put("translate", text.translationKey());
      if (!text.arguments().isEmpty()) tree.put("with", trees(text.arguments(), protocol));
      if (text.fallback() != null && ProtocolEras.textFallback(protocol)) tree.put("fallback", text.fallback());
    } else {
      tree.put("text", text.content());
    }
    TextColor color = text.color();
    if (color != null) tree.put("color", (ProtocolEras.textRgb(protocol) ? color : color.nearestNamed()).colorName());
    for (Text.Decoration decoration : Text.Decoration.values()) {
      Boolean state = text.decoration(decoration);
      if (state != null) tree.put(decoration.key(), state);
    }
    if (text.insertion() != null && ProtocolEras.textInsertion(protocol)) tree.put("insertion", text.insertion());
    if (text.clickEvent() != null) click(tree, text.clickEvent(), protocol);
    if (text.hover() != null) {
      boolean snakeCase = ProtocolEras.textSnakeCaseEvents(protocol);
      Map<String, Object> hover = new LinkedHashMap<>();
      hover.put("action", "show_text");
      hover.put(snakeCase || !ProtocolEras.textRgb(protocol) ? "value" : "contents", tree(text.hover(), protocol));
      tree.put(snakeCase ? "hover_event" : "hoverEvent", hover);
    }
    if (!text.children().isEmpty()) tree.put("extra", trees(text.children(), protocol));
    return tree;
  }

  private static List<Object> trees(List<Text> texts, int protocol) {
    List<Object> trees = new ArrayList<>(texts.size());
    for (Text text : texts) trees.add(tree(text, protocol));
    return trees;
  }

  /** Adds the click, in the release's form, or nothing when the release could not read it. */
  private static void click(Map<String, Object> tree, Text.ClickEvent click, int protocol) {
    Text.ClickEvent.Action action = click.action();
    if (action == Text.ClickEvent.Action.COPY_TO_CLIPBOARD && !ProtocolEras.textCopyToClipboard(protocol)) return;
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("action", action.name().toLowerCase(Locale.ROOT));
    if (!ProtocolEras.textSnakeCaseEvents(protocol)) {
      event.put("value", click.value());
      tree.put("clickEvent", event);
      return;
    }
    // From 1.21.5 a click the client cannot parse fails the whole component, and with it the packet.
    String value = click.value();
    switch (action) {
      case OPEN_URL -> {
        if (!httpUrl(value)) return;
        event.put("url", value);
      }
      case RUN_COMMAND, SUGGEST_COMMAND -> {
        if (!chatSafe(value)) return;
        event.put("command", value);
      }
      case CHANGE_PAGE -> {
        Integer page = positive(value);
        if (page == null) return;
        event.put("page", page);
      }
      case COPY_TO_CLIPBOARD -> event.put("value", value);
    }
    tree.put("click_event", event);
  }

  private static boolean httpUrl(String value) {
    try {
      String scheme = new java.net.URI(value).getScheme();
      return scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
    } catch (java.net.URISyntaxException invalid) {
      return false;
    }
  }

  /** Whether chat can hold every character: no section sign, no control characters. */
  private static boolean chatSafe(String value) {
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character == '§' || character < ' ' || character == 0x7F) return false;
    }
    return true;
  }

  private static Integer positive(String value) {
    try {
      int page = Integer.parseInt(value.trim());
      return page > 0 ? page : null;
    } catch (NumberFormatException notNumber) {
      return null;
    }
  }

  /**
   * Reads a JSON component back into Text, for a reason or description a backend wrote. Literal and
   * translatable components keep their style, events and children. Keybind, score, selector and NBT
   * components, and show-item and show-entity hovers, have no place in Text: such a component keeps
   * its style and children around empty text, and such a hover is dropped. Something that is not
   * JSON at all is taken as literal text.
   */
  public static Text fromJson(String json) {
    Object tree = ComponentCodec.parseJson(json);
    return tree == null ? Text.of(json) : fromTree(tree);
  }

  /** {@link #fromJson} for a component already parsed by {@link ComponentCodec#parseJson}, in either release's key names. */
  public static Text fromTree(Object node) {
    if (node instanceof List<?> list) {
      // A bare array is its first element with the rest appended, which is how the client reads it.
      if (list.isEmpty()) return Text.empty();
      Text first = fromTree(list.getFirst());
      for (Object rest : list.subList(1, list.size())) first = first.append(fromTree(rest));
      return first;
    }
    if (!(node instanceof Map<?, ?> map)) return Text.of(node == null ? "" : String.valueOf(node));
    // The client tries "text" before "translate", so a component with both is literal.
    Object content = map.get("text");
    Text text;
    if (content == null && map.get("translate") instanceof String key) {
      List<Text> arguments = new ArrayList<>();
      if (map.get("with") instanceof List<?> with) for (Object argument : with) arguments.add(fromTree(argument));
      text = Text.translatable(key, arguments.toArray(Text[]::new));
      if (map.get("fallback") instanceof String fallback) text = text.fallback(fallback);
    } else {
      text = Text.of(content == null || content instanceof Map || content instanceof List ? "" : String.valueOf(content));
    }
    if (map.get("color") instanceof String name) text = text.color(TextColor.parse(name).orElse(null));
    for (Text.Decoration decoration : Text.Decoration.values()) {
      if (map.get(decoration.key()) instanceof Boolean state) text = text.decoration(decoration, state);
    }
    if (map.get("insertion") instanceof String insertion) text = text.insertion(insertion);
    Object click = map.get("clickEvent") != null ? map.get("clickEvent") : map.get("click_event");
    if (click instanceof Map<?, ?> event) text = text.click(click(event));
    Object hover = map.get("hoverEvent") != null ? map.get("hoverEvent") : map.get("hover_event");
    if (hover instanceof Map<?, ?> event && "show_text".equals(event.get("action"))) {
      Object shown = event.get("contents") != null ? event.get("contents") : event.get("value");
      if (shown == null) shown = event.get("text");
      if (shown != null) text = text.hover(fromTree(shown));
    }
    if (map.get("extra") instanceof List<?> extra) {
      for (Object child : extra) text = text.append(fromTree(child));
    }
    return text;
  }

  /** A click in either release's form, or null for an action Text does not carry. */
  private static Text.ClickEvent click(Map<?, ?> event) {
    if (!(event.get("action") instanceof String action)) return null;
    Object value = event.get("value");
    for (String key : new String[] {"url", "command", "page"}) if (value == null) value = event.get(key);
    if (value == null) return null;
    for (Text.ClickEvent.Action known : Text.ClickEvent.Action.values()) {
      if (known.name().toLowerCase(Locale.ROOT).equals(action)) return new Text.ClickEvent(known, String.valueOf(value));
    }
    return null;
  }

  /**
   * Text as one string with section-sign formatting codes. A 1.8 client shows a game-info chat line
   * (its action bar) as the component's unformatted text, so colour and style reach it only as codes
   * inside that text: RGB as the nearest named colour, each decoration as its code. Clicks, hovers
   * and insertion have nowhere to go there and are dropped; a translation reads as its fallback or key.
   */
  public static String toLegacy(Text text) {
    StringBuilder out = new StringBuilder();
    appendLegacy(out, text, null, EnumSet.noneOf(Text.Decoration.class));
    return out.toString();
  }

  private static void appendLegacy(StringBuilder out, Text text, TextColor inherited, EnumSet<Text.Decoration> inheritedOn) {
    TextColor color = text.color() != null ? text.color() : inherited;
    EnumSet<Text.Decoration> on = EnumSet.copyOf(inheritedOn);
    for (Text.Decoration decoration : Text.Decoration.values()) {
      Boolean state = text.decoration(decoration);
      if (Boolean.TRUE.equals(state)) on.add(decoration);
      else if (Boolean.FALSE.equals(state)) on.remove(decoration);
    }
    String own = text.ownText();
    if (!own.isEmpty()) {
      // A colour code also ends every decoration, so each run states its whole style again, and a
      // run with no colour after a styled one has to reset first.
      if (color != null || !on.isEmpty() || out.length() > 0) {
        out.append('§').append(color == null ? 'r' : Character.forDigit(TextColor.named().indexOf(color.nearestNamed()), 16));
      }
      for (Text.Decoration decoration : on) out.append('§').append(legacyCode(decoration));
      out.append(own);
    }
    for (Text child : text.children()) appendLegacy(out, child, color, on);
  }

  private static char legacyCode(Text.Decoration decoration) {
    return switch (decoration) {
      case BOLD -> 'l';
      case ITALIC -> 'o';
      case UNDERLINED -> 'n';
      case STRIKETHROUGH -> 'm';
      case OBFUSCATED -> 'k';
    };
  }

  public static void writePlainNbt(DataOutput output, String text) throws IOException {
    NetworkNbt.stringComponent(output, text);
  }
}
