package gg.tame.conduit.text;

import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.NetworkNbt;
import java.io.DataOutput;
import java.io.IOException;
import java.util.List;

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
    json.append("\"text\":\"").append(escape(text.content())).append('"');
    if (text.color() != null) json.append(",\"color\":\"").append(text.color().colorName()).append('"');
    if (text.isBold()) json.append(",\"bold\":true");
    if (text.isItalic()) json.append(",\"italic\":true");
    if (text.clickCommand() != null) {
      json.append(",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"")
          .append(escape(text.clickCommand())).append("\"}");
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

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  public static void writePlainNbt(DataOutput output, String text) throws IOException {
    NetworkNbt.stringComponent(output, text);
  }
}
