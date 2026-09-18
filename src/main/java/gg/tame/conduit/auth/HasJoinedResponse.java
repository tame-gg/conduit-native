// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.auth;

import gg.tame.conduit.login.ProfileProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Strict subset parser for Mojang hasJoined JSON. */
public final class HasJoinedResponse {
  private HasJoinedResponse() { }
  public static Result parse(String json) throws AuthenticationException {
    if (json == null || json.isBlank()) throw new AuthenticationException("empty authentication response");
    try {
      Reader reader = new Reader(json);
      reader.skipWhitespace();
      reader.expect('{');
      String id = null, name = null;
      List<ProfileProperty> properties = List.of();
      while (true) {
        reader.skipWhitespace();
        if (reader.peek() == '}') { reader.take(); break; }
        String key = reader.string();
        reader.skipWhitespace(); reader.expect(':'); reader.skipWhitespace();
        switch (key) {
          case "id" -> id = reader.string();
          case "name" -> name = reader.string();
          case "properties" -> properties = properties(reader);
          default -> reader.skipValue();
        }
        reader.skipWhitespace();
        if (reader.peek() == ',') { reader.take(); continue; }
        if (reader.peek() == '}') { reader.take(); break; }
        throw new AuthenticationException("malformed authentication response");
      }
      if (id == null || name == null) throw new AuthenticationException("authentication response missing id or name");
      return new Result(uuid(id), name, properties);
    } catch (AuthenticationException exception) { throw exception; }
    catch (RuntimeException exception) { throw new AuthenticationException("malformed authentication response", exception); }
  }
  private static List<ProfileProperty> properties(Reader reader) throws AuthenticationException {
    reader.expect('['); reader.skipWhitespace();
    List<ProfileProperty> result = new ArrayList<>();
    if (reader.peek() == ']') { reader.take(); return List.copyOf(result); }
    while (true) {
      reader.skipWhitespace(); reader.expect('{');
      String name = null, value = null, signature = null;
      while (true) {
        reader.skipWhitespace();
        if (reader.peek() == '}') { reader.take(); break; }
        String key = reader.string();
        reader.skipWhitespace(); reader.expect(':'); reader.skipWhitespace();
        switch (key) {
          case "name" -> name = reader.string();
          case "value" -> value = reader.string();
          case "signature" -> signature = reader.string();
          default -> reader.skipValue();
        }
        reader.skipWhitespace();
        if (reader.peek() == ',') { reader.take(); continue; }
        if (reader.peek() == '}') { reader.take(); break; }
        throw new AuthenticationException("malformed profile property");
      }
      if (name == null || value == null) throw new AuthenticationException("profile property missing name or value");
      result.add(new ProfileProperty(name, value, Optional.ofNullable(signature)));
      reader.skipWhitespace();
      if (reader.peek() == ',') { reader.take(); continue; }
      if (reader.peek() == ']') { reader.take(); break; }
      throw new AuthenticationException("malformed properties array");
    }
    return List.copyOf(result);
  }
  public static UUID uuid(String hex) throws AuthenticationException {
    String digits = hex.replace("-", "");
    if (digits.length() != 32 || !digits.chars().allMatch(ch -> Character.digit(ch, 16) >= 0)) throw new AuthenticationException("invalid UUID");
    return new UUID(Long.parseUnsignedLong(digits.substring(0, 16), 16), Long.parseUnsignedLong(digits.substring(16), 16));
  }
  public record Result(UUID uniqueId, String username, List<ProfileProperty> properties) { }

  private static final class Reader {
    private final String text; private int index;
    private Reader(String text) { this.text = text; }
    private char peek() { if (index >= text.length()) throw new IllegalStateException("truncated JSON"); return text.charAt(index); }
    private char take() { char value = peek(); index++; return value; }
    private void expect(char expected) { if (take() != expected) throw new IllegalStateException("expected " + expected); }
    private void skipWhitespace() { while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++; }
    private String string() {
      expect('"');
      StringBuilder builder = new StringBuilder();
      while (true) {
        char current = take();
        if (current == '"') return builder.toString();
        if (current == '\\') {
          char escaped = take();
          builder.append(switch (escaped) { case '"' -> '"'; case '\\' -> '\\'; case '/' -> '/'; case 'n' -> '\n'; case 't' -> '\t'; default -> escaped; });
        } else builder.append(current);
      }
    }
    private void skipValue() {
      skipWhitespace();
      char current = peek();
      if (current == '"') { string(); return; }
      if (current == '{') { skipContainer('{', '}'); return; }
      if (current == '[') { skipContainer('[', ']'); return; }
      while (index < text.length()) {
        char next = peek();
        if (next == ',' || next == '}' || next == ']' || Character.isWhitespace(next)) return;
        take();
      }
    }
    private void skipContainer(char open, char close) {
      expect(open); int depth = 1;
      while (depth > 0) {
        char current = take();
        if (current == '"') { index--; string(); }
        else if (current == open) depth++;
        else if (current == close) depth--;
      }
    }
  }
}
