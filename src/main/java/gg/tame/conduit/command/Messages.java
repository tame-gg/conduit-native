package gg.tame.conduit.command;

import gg.tame.conduit.Conduit;
import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.api.text.TextColor;

/**
 * Tasteful Conduit chat styling. Hierarchy and color only — no boxes, dashboards, or click events.
 */
public final class Messages {
  static final TextColor BRAND = TextColor.AQUA;
  static final TextColor LABEL = TextColor.GRAY;
  static final TextColor BODY = TextColor.WHITE;
  static final TextColor CURRENT = TextColor.GREEN;
  static final TextColor OTHER = TextColor.DARK_GRAY;
  static final TextColor OK = TextColor.GREEN;
  static final TextColor WARN = TextColor.GOLD;
  static final TextColor BAD = TextColor.RED;

  private Messages() {}

  public static void brandLine(CommandSource source) {
    source.sendMessage(Text.of(Conduit.BRAND).color(BRAND).bold());
  }

  public static void info(CommandSource source, String message) {
    source.sendMessage(Text.of(message).color(LABEL));
  }

  public static void body(CommandSource source, String message) {
    source.sendMessage(Text.of(message).color(BODY));
  }

  public static void connecting(CommandSource source, String server) {
    source.sendMessage(Text.of("Connecting to ").color(LABEL)
        .append(Text.of(server).color(BRAND))
        .append(Text.of("...").color(LABEL)));
  }

  public static void connected(CommandSource source, String server) {
    source.sendMessage(Text.of("Connected to ").color(OK)
        .append(Text.of(server).color(BODY).bold())
        .append(Text.of(".").color(OK)));
  }

  public static void unavailable(CommandSource source, String server) {
    source.sendMessage(Text.of(server).color(BAD)
        .append(Text.of(" is unavailable. Please try again later.").color(LABEL)));
  }

  public static void success(CommandSource source, String message) {
    source.sendMessage(Text.of("✓ ").color(OK).append(Text.of(message).color(BODY)));
  }

  public static void failure(CommandSource source, String message) {
    source.sendMessage(Text.of("✕ ").color(BAD).append(Text.of(message).color(BODY)));
  }

  public static void permission(CommandSource source) {
    source.sendMessage(Text.of("You don't have permission to use this command.").color(BAD));
  }

  public static void alreadyConnected(CommandSource source, String server) {
    source.sendMessage(Text.of("You are already connected to ").color(LABEL)
        .append(Text.of(server).color(CURRENT).bold())
        .append(Text.of(".").color(LABEL)));
  }
}
