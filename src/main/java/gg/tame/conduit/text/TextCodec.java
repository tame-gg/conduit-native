// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.text;

import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.api.text.TextColor;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.NetworkNbt;
import gg.tame.conduit.protocol.text.ComponentCodec;
import java.io.DataOutput;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Encodes native {@link Text} to network NBT or JSON chat components. */
public final class TextCodec {
  private TextCodec() {}

  public static void write(DataOutput output, Text text, boolean networkNbt) throws IOException {
    if (networkNbt) writeNbt(output, text);
    else MinecraftOutput.string(output, toJson(text));
  }

  public static String toJson(Text text) {
    StringBuilder json = new StringBuilder();
    writeJson(json, text);
    return json.toString();
  }

  private static void writeJson(StringBuilder json, Text text) {
    json.append('{');
    json.append("\"text\":");
    ComponentCodec.quote(json, text.content());
    if (text.color() != null) json.append(",\"color\":\"").append(text.color().colorName()).append('"');
    if (text.isBold()) json.append(",\"bold\":true");
    if (text.isItalic()) json.append(",\"italic\":true");
    if (text.clickCommand() != null) {
      json.append(",\"clickEvent\":{\"action\":\"run_command\",\"value\":");
      ComponentCodec.quote(json, text.clickCommand());
      json.append('}');
    }
    if (text.hover() != null) {
      json.append(",\"hoverEvent\":{\"action\":\"show_text\",\"contents\":");
      writeJson(json, text.hover());
      json.append('}');
    }
    List<Text> children = text.children();
    if (!children.isEmpty()) {
      json.append(",\"extra\":[");
      for (int i = 0; i < children.size(); i++) {
        if (i > 0) json.append(',');
        writeJson(json, children.get(i));
      }
      json.append(']');
    }
    json.append('}');
  }

  private static void writeNbt(DataOutput output, Text text) throws IOException {
    output.writeByte(10);
    writeNbtBody(output, text);
  }

  private static void writeNbtBody(DataOutput output, Text text) throws IOException {
    writeStringTag(output, "text", text.content());
    if (text.color() != null) writeStringTag(output, "color", text.color().colorName());
    if (text.isBold()) writeByteTag(output, "bold", (byte) 1);
    if (text.isItalic()) writeByteTag(output, "italic", (byte) 1);
    if (text.clickCommand() != null) {
      output.writeByte(10);
      output.writeUTF("clickEvent");
      writeStringTag(output, "action", "run_command");
      writeStringTag(output, "value", text.clickCommand());
      output.writeByte(0);
    }
    if (text.hover() != null) {
      output.writeByte(10);
      output.writeUTF("hoverEvent");
      writeStringTag(output, "action", "show_text");
      output.writeByte(10);
      output.writeUTF("contents");
      writeNbtBody(output, text.hover());
      output.writeByte(0);
    }
    List<Text> children = text.children();
    if (!children.isEmpty()) {
      output.writeByte(9);
      output.writeUTF("extra");
      output.writeByte(10);
      output.writeInt(children.size());
      for (Text child : children) writeNbtBody(output, child);
    }
    output.writeByte(0);
  }

  private static void writeStringTag(DataOutput output, String name, String value) throws IOException {
    output.writeByte(8);
    output.writeUTF(name);
    output.writeUTF(value == null ? "" : value);
  }

  private static void writeByteTag(DataOutput output, String name, byte value) throws IOException {
    output.writeByte(1);
    output.writeUTF(name);
    output.writeByte(value);
  }

  /**
   * Reads a JSON component back into Text, for a reason a backend wrote. Text has no translatable
   * or score parts: a translatable arrives as its key, and colours outside the sixteen named ones
   * are dropped. Something that is not JSON at all is taken as literal text.
   */
  public static Text fromJson(String json) {
    Object tree = ComponentCodec.parseJson(json);
    return tree == null ? Text.of(json) : fromTree(tree);
  }

  /** {@link #fromJson} for a component already parsed by {@link ComponentCodec#parseJson}. */
  public static Text fromTree(Object node) {
    if (node instanceof List<?> list) {
      // A bare array is its first element with the rest appended, which is how the client reads it.
      if (list.isEmpty()) return Text.empty();
      Text first = fromTree(list.getFirst());
      for (Object rest : list.subList(1, list.size())) first = first.append(fromTree(rest));
      return first;
    }
    if (!(node instanceof Map<?, ?> map)) return Text.of(node == null ? "" : String.valueOf(node));
    Object content = map.get("text") != null ? map.get("text") : map.get("translate");
    Text text = Text.of(content instanceof String string ? string : "");
    if (map.get("color") instanceof String name) {
      for (TextColor color : TextColor.values()) if (color.colorName().equals(name)) text = text.color(color);
    }
    if (Boolean.TRUE.equals(map.get("bold"))) text = text.bold();
    if (Boolean.TRUE.equals(map.get("italic"))) text = text.italic();
    if (map.get("clickEvent") instanceof Map<?, ?> click && "run_command".equals(click.get("action"))
        && click.get("value") instanceof String command) {
      text = text.clickRun(command);
    }
    if (map.get("hoverEvent") instanceof Map<?, ?> hover && "show_text".equals(hover.get("action"))) {
      Object shown = hover.get("contents") != null ? hover.get("contents") : hover.get("value");
      if (shown != null) text = text.hover(fromTree(shown));
    }
    if (map.get("extra") instanceof List<?> extra) {
      for (Object child : extra) text = text.append(fromTree(child));
    }
    return text;
  }

  /**
   * Text as one string with section-sign formatting codes. A 1.8 client shows a game-info chat line
   * (its action bar) as the component's unformatted text, so colour and style reach it only as codes
   * inside that text. Clicks and hovers have nowhere to go there and are dropped.
   */
  public static String toLegacy(Text text) {
    StringBuilder out = new StringBuilder();
    appendLegacy(out, text, null, false, false);
    return out.toString();
  }

  private static void appendLegacy(StringBuilder out, Text text, TextColor inherited, boolean bold, boolean italic) {
    TextColor color = text.color() != null ? text.color() : inherited;
    boolean isBold = bold || text.isBold();
    boolean isItalic = italic || text.isItalic();
    if (!text.content().isEmpty()) {
      // A colour code also ends bold and italic, so each run states its whole style again, and a
      // run with no colour after a styled one has to reset first.
      if (color != null || isBold || isItalic || out.length() > 0) {
        // TextColor is declared in legacy code order, 0 (black) to f (white).
        out.append('§').append(color == null ? 'r' : Character.forDigit(color.ordinal(), 16));
      }
      if (isBold) out.append("§l");
      if (isItalic) out.append("§o");
      out.append(text.content());
    }
    for (Text child : text.children()) appendLegacy(out, child, color, isBold, isItalic);
  }

  public static void writePlainNbt(DataOutput output, String text) throws IOException {
    NetworkNbt.stringComponent(output, text);
  }
}
