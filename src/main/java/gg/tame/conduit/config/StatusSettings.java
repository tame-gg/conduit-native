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
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * What the server list shows before plugins change it. {@code displayMaxPlayers} is only the
 * number printed after the slash: Conduit has no join cap, and this is not one.
 */
public record StatusSettings(Text motd, int displayMaxPlayers, Optional<String> favicon,
                             FaviconPolicy faviconPolicy) {
  /**
   * Who decides the icon a client is sent.
   *
   * <p>{@code PLUGINS} is how Conduit has always answered: a {@code ServerListPingEvent} listener,
   * or a Velocity plugin through {@code ProxyPingEvent}, may set whatever icon it likes and that is
   * what the client gets. {@code PROXY_ONLY} takes the icon back after every listener has had its
   * say, so what the server list shows is the operator's own file or nothing at all. It exists for
   * the deployment where a plugin pings a backend and hands the answer on: the backend's icon is
   * not this proxy's identity, and an operator who has decided that wants it settled here rather
   * than in each plugin. Nothing else about the answer is touched.
   */
  public enum FaviconPolicy {
    PLUGINS,
    PROXY_ONLY;

    public static FaviconPolicy parse(String written) {
      String text = written == null ? "" : written.strip().toLowerCase(java.util.Locale.ROOT);
      return switch (text) {
        case "", "plugins" -> PLUGINS;
        case "proxy-only" -> PROXY_ONLY;
        default -> throw new IllegalArgumentException(
            "status.favicon-policy must be \"plugins\" or \"proxy-only\"");
      };
    }
  }

  /** The policy Conduit has always had: whatever a plugin sets is what the client is sent. */
  public StatusSettings(Text motd, int displayMaxPlayers, Optional<String> favicon) {
    this(motd, displayMaxPlayers, favicon, FaviconPolicy.PLUGINS);
  }

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
    if (faviconPolicy == null) faviconPolicy = FaviconPolicy.PLUGINS;
    if (displayMaxPlayers < 0) throw new IllegalArgumentException("status.display-max-players must be >= 0");
  }

  public static StatusSettings defaults() {
    return new StatusSettings(Text.of(DEFAULT_MOTD), DEFAULT_DISPLAY_MAX_PLAYERS, Optional.empty());
  }

  /**
   * Reads a configured MOTD into Text, in either of the two forms server owners write.
   *
   * <p>MiniMessage first, which is what Velocity's own {@code motd} takes: {@code <red>},
   * {@code <bold>}, {@code <gradient:#a:#b>}, {@code <hover:show_text:'...'>} and the rest, through
   * the Adventure that ships with the Velocity plugin runtime.
   *
   * <p>A string MiniMessage leaves exactly as it found it has no tags in it, and is read as the
   * {@code &} codes Conduit has always taken instead: {@code &0}-{@code &9} and {@code &a}-{@code &f}
   * pick a colour and, as in the game, clear every decoration; {@code &l} is bold, {@code &o}
   * italic, {@code &n} underlined, {@code &m} strikethrough, {@code &k} obfuscated, {@code &r} back
   * to plain. {@code \n} starts the second line. An {@code &} before anything else is kept as it is.
   * A MOTD with neither comes back as the plain text it is, either way round.
   */
  public static Text parseMotd(String raw) {
    // Comparing against the text MiniMessage produced, rather than guessing from the string whether
    // it holds a tag: "Welcome <3" and "A > B" are not tags, and a reader that thought they were
    // would quietly drop half a MOTD that has worked for years.
    Text minimessage = gg.tame.conduit.text.MiniMessages.parse(raw);
    if (minimessage != null && !minimessage.plain().equals(raw)) return minimessage;
    return legacyMotd(raw);
  }

  /** The {@code &}-code reader, which is also what a MOTD with no formatting at all goes through. */
  private static Text legacyMotd(String raw) {
    List<Text> segments = new ArrayList<>();
    StringBuilder run = new StringBuilder();
    TextColor color = null;
    EnumSet<Text.Decoration> on = EnumSet.noneOf(Text.Decoration.class);
    for (int index = 0; index < raw.length(); index++) {
      char character = raw.charAt(index);
      char next = index + 1 < raw.length() ? Character.toLowerCase(raw.charAt(index + 1)) : 0;
      if (character == '\\' && next == 'n') { run.append('\n'); index++; continue; }
      if (character != '&' || "0123456789abcdefklmnor".indexOf(next) < 0) { run.append(character); continue; }
      index++;
      addSegment(segments, run, color, on);
      int colour = "0123456789abcdef".indexOf(next);
      int decoration = "lonmk".indexOf(next);
      if (colour >= 0) { color = TextColor.named().get(colour); on.clear(); }
      else if (decoration >= 0) on.add(DECORATION_CODES.get(decoration));
      else { color = null; on.clear(); }
    }
    addSegment(segments, run, color, on);
    if (segments.isEmpty()) return Text.empty();
    // A MOTD with no codes is one plain component, exactly what the list was sent before codes.
    return segments.size() == 1 ? segments.getFirst() : Text.join(segments.toArray(Text[]::new));
  }

  /** The decorations {@code l}, {@code o}, {@code n}, {@code m} and {@code k} turn on, in that order. */
  private static final List<Text.Decoration> DECORATION_CODES = List.of(Text.Decoration.BOLD, Text.Decoration.ITALIC,
      Text.Decoration.UNDERLINED, Text.Decoration.STRIKETHROUGH, Text.Decoration.OBFUSCATED);

  private static void addSegment(List<Text> segments, StringBuilder run, TextColor color, EnumSet<Text.Decoration> on) {
    if (run.isEmpty()) return;
    Text segment = Text.of(run.toString());
    if (color != null) segment = segment.color(color);
    for (Text.Decoration decoration : on) segment = segment.decorate(decoration);
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
