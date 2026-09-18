// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.config;

import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.api.text.TextColor;
import gg.tame.conduit.log.ConduitLog;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * What the server list shows before plugins change it. {@code displayMaxPlayers} is only the
 * number printed after the slash: Conduit has no join cap, and this is not one.
 */
public record StatusSettings(Text motd, int displayMaxPlayers, Optional<String> favicon) {
  public static final String DEFAULT_MOTD = "Conduit";
  public static final int DEFAULT_DISPLAY_MAX_PLAYERS = 100;
  /**
   * A client reads the whole status answer as one string of at most 32767 characters, and one that
   * is longer fails to parse and shows the server as unreachable. The favicon is by far the largest
   * part of it, so it gets most of that room and the MOTD and player sample keep the rest.
   */
  static final int MAX_FAVICON_CHARS = 28_000;

  public StatusSettings {
    if (motd == null) motd = Text.of(DEFAULT_MOTD);
    if (favicon == null) favicon = Optional.empty();
    if (displayMaxPlayers < 0) throw new IllegalArgumentException("status.display-max-players must be >= 0");
  }

  public static StatusSettings defaults() {
    return new StatusSettings(Text.of(DEFAULT_MOTD), DEFAULT_DISPLAY_MAX_PLAYERS, Optional.empty());
  }

  /**
   * Reads a configured MOTD into Text, using the formatting codes server owners already write.
   *
   * <p>{@code &0}-{@code &9} and {@code &a}-{@code &f} pick a colour and, as in the game, clear bold
   * and italic; {@code &l} is bold, {@code &o} italic, {@code &r} back to plain. {@code \n} starts
   * the second line. {@code &k}, {@code &m} and {@code &n} are dropped, because Text has nothing to
   * carry them in. An {@code &} before anything else is kept as it is.
   */
  public static Text parseMotd(String raw) {
    List<Text> segments = new ArrayList<>();
    StringBuilder run = new StringBuilder();
    TextColor color = null;
    boolean bold = false;
    boolean italic = false;
    for (int index = 0; index < raw.length(); index++) {
      char character = raw.charAt(index);
      char next = index + 1 < raw.length() ? Character.toLowerCase(raw.charAt(index + 1)) : 0;
      if (character == '\\' && next == 'n') { run.append('\n'); index++; continue; }
      if (character != '&' || "0123456789abcdefklmnor".indexOf(next) < 0) { run.append(character); continue; }
      index++;
      if ("kmn".indexOf(next) >= 0) continue;
      addSegment(segments, run, color, bold, italic);
      int colour = "0123456789abcdef".indexOf(next);
      // TextColor is declared in the order of these codes, black through white.
      if (colour >= 0) { color = TextColor.values()[colour]; bold = false; italic = false; }
      else if (next == 'l') bold = true;
      else if (next == 'o') italic = true;
      else { color = null; bold = false; italic = false; }
    }
    addSegment(segments, run, color, bold, italic);
    if (segments.isEmpty()) return Text.empty();
    // A MOTD with no codes is one plain component, exactly what the list was sent before codes.
    return segments.size() == 1 ? segments.getFirst() : Text.join(segments.toArray(Text[]::new));
  }

  private static void addSegment(List<Text> segments, StringBuilder run, TextColor color, boolean bold, boolean italic) {
    if (run.isEmpty()) return;
    Text segment = Text.of(run.toString());
    if (color != null) segment = segment.color(color);
    if (bold) segment = segment.bold();
    if (italic) segment = segment.italic();
    segments.add(segment);
    run.setLength(0);
  }

  /**
   * The favicon at {@code file} as the data URI the status answer carries, or empty -- with the
   * reason logged -- when it is unreadable, not a 64x64 PNG, or too large to fit in the answer. A
   * bad favicon costs the server its icon, not its listing: sent anyway, the client rejects the
   * whole answer and shows the server as unreachable.
   */
  public static Optional<String> favicon(Path file) {
    byte[] png;
    try { png = Files.readAllBytes(file); }
    catch (IOException unreadable) {
      ConduitLog.warn("status.favicon " + file + " could not be read, so the server list shows no icon: " + unreadable);
      return Optional.empty();
    }
    // IHDR is always the first chunk: the 8-byte signature, the chunk's length and type, then
    // width and height as big-endian ints.
    boolean png64 = png.length >= 24
        && ByteBuffer.wrap(png, 0, 8).getLong() == 0x89504E470D0A1A0AL
        && ByteBuffer.wrap(png, 12, 4).getInt() == 0x49484452
        && ByteBuffer.wrap(png, 16, 4).getInt() == 64
        && ByteBuffer.wrap(png, 20, 4).getInt() == 64;
    if (!png64) {
      ConduitLog.warn("status.favicon " + file + " is not a 64x64 PNG, so the server list shows no icon");
      return Optional.empty();
    }
    String uri = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
    if (uri.length() > MAX_FAVICON_CHARS) {
      ConduitLog.warn("status.favicon " + file + " is " + png.length + " bytes, too large for a status answer; "
          + "the server list shows no icon (keep it under about 20 KB)");
      return Optional.empty();
    }
    return Optional.of(uri);
  }
}
