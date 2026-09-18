// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol.text;

import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Text components across the 1.13 / 1.20.4 boundary.
 *
 * <p>Up to 1.20.2 a text component travelled as a JSON string. From 1.20.3 the
 * same component travels as network NBT. The <em>structure</em> did not change —
 * the same keys, the same nesting, the same {@code extra} list — so this is a
 * representation change, not a semantic one, and it can be converted both ways
 * without losing colour, hover text or translation keys the way "extract the
 * plain text" does.
 *
 * <p>The JSON parser here is deliberately small and total: it accepts the subset
 * Minecraft actually emits (objects, arrays, strings, numbers, booleans, null)
 * and never throws on trailing content. A component that cannot be parsed falls
 * back to being carried as literal text, which both eras accept — an NBT string
 * on its own is a valid modern component.
 */
public final class ComponentCodec {
  private ComponentCodec() {}

  /** Writes a JSON component as a nameless network-NBT component (1.20.3+ form). */
  public static void jsonToNbt(DataOutput output, String json) throws IOException {
    Object value = Json.parse(json);
    if (value == null) value = json;              // unparseable: carry as literal text
    writeTag(output, value, true);
  }

  /** Reads a nameless network-NBT component and renders it as a JSON component. */
  public static String nbtToJson(DataInput input) throws IOException {
    Object value = readTag(input, input.readUnsignedByte());
    return Json.write(value);
  }

  /** The JSON form of a component that is simply literal text. */
  public static String literalJson(String text) {
    StringBuilder out = new StringBuilder("{\"text\":");
    Json.quote(out, text);
    return out.append('}').toString();
  }

  /** A JSON component as a tree of String, List, Map, Integer, Double and Boolean, or null when it does not parse. */
  public static Object parseJson(String json) { return Json.parse(json); }

  /** A component tree, of the kinds {@link #parseJson} returns, as JSON. */
  public static String toJson(Object tree) { return Json.write(tree); }

  /** A component tree, of the kinds {@link #parseJson} returns, as a nameless network-NBT component. */
  public static void writeNbt(DataOutput output, Object tree) throws IOException { writeTag(output, tree, true); }

  /** Appends {@code value} as a JSON string, escaping quotes, backslashes and control characters. */
  public static void quote(StringBuilder out, String value) { Json.quote(out, value); }

  /** Best-effort plain text, for places that genuinely cannot carry structure. */
  public static String plain(Object component) {
    StringBuilder text = new StringBuilder();
    flatten(component, text);
    return text.toString();
  }

  private static void flatten(Object component, StringBuilder text) {
    if (component instanceof String string) { text.append(string); return; }
    if (component instanceof List<?> list) { list.forEach(child -> flatten(child, text)); return; }
    if (component instanceof Map<?, ?> map) {
      Object value = map.get("text");
      if (value instanceof String string) text.append(string);
      Object extra = map.get("extra");
      if (extra != null) flatten(extra, text);
    }
  }

  // ------------------------------------------------------------------- to NBT

  private static void writeTag(DataOutput output, Object value, boolean withType) throws IOException {
    if (value instanceof String string) {
      if (withType) output.writeByte(8);
      output.writeUTF(string);
    } else if (value instanceof Boolean flag) {
      if (withType) output.writeByte(1);
      output.writeByte(flag ? 1 : 0);
    } else if (value instanceof Integer number) {
      if (withType) output.writeByte(3);
      output.writeInt(number);
    } else if (value instanceof Double number) {
      if (withType) output.writeByte(6);
      output.writeDouble(number);
    } else if (value instanceof List<?> list) {
      writeList(output, list, withType);
    } else if (value instanceof Map<?, ?> map) {
      if (withType) output.writeByte(10);
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        Object child = entry.getValue();
        if (child == null) continue;
        output.writeByte(nbtType(child));
        output.writeUTF(String.valueOf(entry.getKey()));
        writeTag(output, child, false);
      }
      output.writeByte(0);
    } else {
      // null and anything unexpected: an empty string is a valid component.
      if (withType) output.writeByte(8);
      output.writeUTF("");
    }
  }

  /**
   * Writes a JSON array as an NBT list.
   *
   * <p>NBT lists are homogeneous, but JSON arrays in text components are not:
   * a translation's {@code with} array routinely holds a bare string next to a
   * nested component, as in {@code commands.give.success.single}. Modern
   * Minecraft handles that by promoting every element to a compound holding the
   * value under an empty key, and its reader unwraps the same shape. Taking the
   * element type from the first item instead and writing the rest as if they
   * matched produces a list the client cannot parse — which it reports only as
   * "Loading NBT data" before dropping the connection.
   */
  private static void writeList(DataOutput output, List<?> list, boolean withType) throws IOException {
    List<?> items = list.stream().filter(java.util.Objects::nonNull).toList();
    if (withType) output.writeByte(9);
    if (items.isEmpty()) {
      output.writeByte(0);
      output.writeInt(0);
      return;
    }
    int element = nbtType(items.get(0));
    boolean homogeneous = items.stream().allMatch(item -> nbtType(item) == element);
    if (homogeneous) {
      output.writeByte(element);
      output.writeInt(items.size());
      for (Object item : items) writeTag(output, item, false);
      return;
    }
    output.writeByte(10);                       // a list of wrapper compounds
    output.writeInt(items.size());
    for (Object item : items) {
      output.writeByte(nbtType(item));
      output.writeUTF("");                      // the empty key modern NBT uses
      writeTag(output, item, false);
      output.writeByte(0);
    }
  }

  private static int nbtType(Object value) {
    if (value instanceof String) return 8;
    if (value instanceof Boolean) return 1;
    if (value instanceof Integer) return 3;
    if (value instanceof Double) return 6;
    if (value instanceof List<?>) return 9;
    if (value instanceof Map<?, ?>) return 10;
    return 8;
  }

  // ----------------------------------------------------------------- from NBT

  private static Object readTag(DataInput input, int type) throws IOException {
    return switch (type) {
      case 0 -> null;
      case 1 -> input.readByte() != 0;
      case 2 -> (int) input.readShort();
      case 3 -> input.readInt();
      case 4 -> (double) input.readLong();
      case 5 -> (double) input.readFloat();
      case 6 -> input.readDouble();
      case 7 -> numberArray(input, 1);
      case 8 -> input.readUTF();
      case 9 -> {
        int element = input.readUnsignedByte();
        int length = input.readInt();
        if (length < 0 || length > 65536) throw new IOException("nbt list " + length);
        List<Object> items = new ArrayList<>(Math.min(length, 64));
        for (int index = 0; index < length; index++) items.add(readTag(input, element));
        // Undo the wrapper a heterogeneous list is stored in: each element is a
        // compound whose only key is the empty string.
        yield items.stream().map(ComponentCodec::unwrap).toList();
      }
      case 10 -> {
        Map<String, Object> map = new LinkedHashMap<>();
        while (true) {
          int child = input.readUnsignedByte();
          if (child == 0) yield map;
          map.put(input.readUTF(), readTag(input, child));
        }
      }
      case 11 -> numberArray(input, 4);
      case 12 -> numberArray(input, 8);
      default -> throw new IOException("unknown nbt tag type " + type);
    };
  }

  private static Object unwrap(Object value) {
    if (value instanceof Map<?, ?> map && map.size() == 1 && map.containsKey("")) {
      return map.get("");
    }
    return value;
  }

  /**
   * Reads a byte/int/long array as a list of numbers.
   *
   * <p>These are not an exotic case in text components: modern NBT encodes a
   * homogeneous list of integers as a TAG_Int_Array, and a translation argument
   * list of counts (commands.fill.success carries one) arrives exactly that way.
   * Discarding the payload and substituting an empty string produced JSON whose
   * `with` was a string where the client requires an array, and a 1.13 client
   * closes the connection on that rather than ignoring it.
   */
  private static Object numberArray(DataInput input, int width) throws IOException {
    int length = input.readInt();
    if (length < 0 || length > 262_144) throw new IOException("nbt array " + length);
    List<Object> values = new ArrayList<>(Math.min(length, 64));
    for (int index = 0; index < length; index++) {
      values.add(switch (width) {
        case 1 -> (int) input.readByte();
        case 4 -> input.readInt();
        default -> (int) input.readLong();
      });
    }
    return values;
  }

  // ------------------------------------------------------- convenience buffers

  public static byte[] jsonToNbtBytes(String json) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream(64);
    jsonToNbt(new DataOutputStream(buffer), json);
    return buffer.toByteArray();
  }

  public static String nbtBytesToJson(byte[] nbt) throws IOException {
    try (DataInputStream input = new DataInputStream(new java.io.ByteArrayInputStream(nbt))) {
      return nbtToJson(input);
    }
  }

  // ------------------------------------------------------------- tiny JSON I/O

  /** Just enough JSON for text components: total, allocation-light, never throws. */
  static final class Json {
    /**
     * How deep objects and arrays may nest, as deep as the client's own NBT reader goes. The parser
     * and everything that walks its tree recurse once per level, so a backend's kick reason or status
     * answer nested a few thousand levels deep overflowed the stack of the connection thread reading
     * it; the error escaped as a fault instead of a text that could not be read.
     */
    private static final int MAX_DEPTH = 512;
    private final String text;
    private int cursor;
    private int depth;

    private Json(String text) { this.text = text; }

    static Object parse(String json) {
      if (json == null) return null;
      try {
        Json parser = new Json(json);
        parser.whitespace();
        Object value = parser.value();
        return value;
      } catch (RuntimeException exception) {
        return null;
      }
    }

    private void descend() {
      if (++depth > MAX_DEPTH) throw new IllegalStateException("json nests too deeply");
    }

    private void whitespace() {
      while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))) cursor++;
    }

    private Object value() {
      whitespace();
      if (cursor >= text.length()) throw new IllegalStateException("end of json");
      char character = text.charAt(cursor);
      return switch (character) {
        case '{' -> object();
        case '[' -> array();
        case '"' -> string();
        case 't' -> literal("true", Boolean.TRUE);
        case 'f' -> literal("false", Boolean.FALSE);
        case 'n' -> literal("null", null);
        default -> number();
      };
    }

    private Object literal(String token, Object result) {
      if (!text.startsWith(token, cursor)) throw new IllegalStateException("bad literal");
      cursor += token.length();
      return result;
    }

    private Object number() {
      int start = cursor;
      while (cursor < text.length() && "+-.eE0123456789".indexOf(text.charAt(cursor)) >= 0) cursor++;
      String token = text.substring(start, cursor);
      if (token.isEmpty()) throw new IllegalStateException("bad number");
      if (token.indexOf('.') < 0 && token.indexOf('e') < 0 && token.indexOf('E') < 0) {
        return Integer.valueOf(token);
      }
      return Double.valueOf(token);
    }

    private Map<String, Object> object() {
      Map<String, Object> map = new LinkedHashMap<>();
      descend();
      cursor++;                                   // '{'
      whitespace();
      if (cursor < text.length() && text.charAt(cursor) == '}') { cursor++; depth--; return map; }
      while (true) {
        whitespace();
        String key = string();
        whitespace();
        if (text.charAt(cursor) != ':') throw new IllegalStateException("expected ':'");
        cursor++;
        map.put(key, value());
        whitespace();
        char next = text.charAt(cursor++);
        if (next == '}') { depth--; return map; }
        if (next != ',') throw new IllegalStateException("expected ',' or '}'");
      }
    }

    private List<Object> array() {
      List<Object> list = new ArrayList<>();
      descend();
      cursor++;                                   // '['
      whitespace();
      if (cursor < text.length() && text.charAt(cursor) == ']') { cursor++; depth--; return list; }
      while (true) {
        list.add(value());
        whitespace();
        char next = text.charAt(cursor++);
        if (next == ']') { depth--; return list; }
        if (next != ',') throw new IllegalStateException("expected ',' or ']'");
      }
    }

    private String string() {
      if (text.charAt(cursor) != '"') throw new IllegalStateException("expected string");
      cursor++;
      StringBuilder out = new StringBuilder();
      while (true) {
        char character = text.charAt(cursor++);
        if (character == '"') return out.toString();
        if (character != '\\') { out.append(character); continue; }
        char escape = text.charAt(cursor++);
        switch (escape) {
          case 'n' -> out.append('\n');
          case 'r' -> out.append('\r');
          case 't' -> out.append('\t');
          case 'b' -> out.append('\b');
          case 'f' -> out.append('\f');
          case 'u' -> {
            out.append((char) Integer.parseInt(text.substring(cursor, cursor + 4), 16));
            cursor += 4;
          }
          default -> out.append(escape);
        }
      }
    }

    static String write(Object value) {
      StringBuilder out = new StringBuilder();
      writeValue(out, value);
      return out.toString();
    }

    private static void writeValue(StringBuilder out, Object value) {
      if (value instanceof String string) { quote(out, string); return; }
      if (value instanceof Boolean || value instanceof Integer || value instanceof Double) {
        out.append(value);
        return;
      }
      if (value instanceof List<?> list) {
        out.append('[');
        for (int index = 0; index < list.size(); index++) {
          if (index > 0) out.append(',');
          writeValue(out, list.get(index));
        }
        out.append(']');
        return;
      }
      if (value instanceof Map<?, ?> map) {
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
          if (!first) out.append(',');
          first = false;
          quote(out, String.valueOf(entry.getKey()));
          out.append(':');
          writeValue(out, entry.getValue());
        }
        out.append('}');
        return;
      }
      out.append("null");
    }

    static void quote(StringBuilder out, String value) {
      out.append('"');
      for (int index = 0; index < value.length(); index++) {
        char character = value.charAt(index);
        switch (character) {
          case '"' -> out.append("\\\"");
          case '\\' -> out.append("\\\\");
          case '\n' -> out.append("\\n");
          case '\r' -> out.append("\\r");
          case '\t' -> out.append("\\t");
          default -> {
            if (character < 0x20) out.append(String.format("\\u%04x", (int) character));
            else out.append(character);
          }
        }
      }
      out.append('"');
    }
  }
}
