package gg.tame.conduit.command;

import gg.tame.conduit.Conduit;
import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.api.text.TextColor;

/** Consistent Conduit chat chrome — polished, not rainbow spam. */
public final class ConduitUi {
  public static final TextColor ACCENT = TextColor.AQUA;
  public static final TextColor TITLE = TextColor.WHITE;
  public static final TextColor MUTED = TextColor.GRAY;
  public static final TextColor LABEL = TextColor.DARK_AQUA;
  public static final TextColor OK = TextColor.GREEN;
  public static final TextColor BAD = TextColor.RED;
  public static final TextColor WARN = TextColor.GOLD;
  public static final TextColor CURRENT = TextColor.GREEN;
  public static final TextColor ONLINE = TextColor.GREEN;
  public static final TextColor OFFLINE = TextColor.DARK_GRAY;

  private ConduitUi() {}

  public static void brandLine(CommandSource source) {
    source.sendMessage(Text.of(Conduit.BRAND).color(ACCENT).bold());
  }

  public static void panelOpen(CommandSource source, String title) {
    source.sendMessage(Text.of("╭── ").color(MUTED)
        .append(Text.of(title).color(ACCENT).bold())
        .append(Text.of(" ──╮").color(MUTED)));
  }

  public static void panelClose(CommandSource source) {
    source.sendMessage(Text.of("╰──────────────────────────────╯").color(MUTED));
  }

  public static void blank(CommandSource source) {
    source.sendMessage(Text.empty());
  }

  public static void row(CommandSource source, String label, String value) {
    source.sendMessage(Text.of("  " + pad(label, 16)).color(LABEL)
        .append(Text.of(value == null ? "—" : value).color(TITLE)));
  }

  public static void muted(CommandSource source, String message) {
    source.sendMessage(Text.of(message).color(MUTED));
  }

  public static void success(CommandSource source, String message) {
    source.sendMessage(Text.of("✓ ").color(OK).bold().append(Text.of(message).color(TITLE)));
  }

  public static void failure(CommandSource source, String message) {
    source.sendMessage(Text.of("✕ ").color(BAD).bold().append(Text.of(message).color(TITLE)));
  }

  public static void failure(CommandSource source, String message, String reason) {
    failure(source, message);
    if (reason != null && !reason.isBlank()) {
      source.sendMessage(Text.of("  ").append(Text.of(reason).color(MUTED)));
    }
  }

  public static void connecting(CommandSource source, String serverDisplay) {
    brandLine(source);
    source.sendMessage(Text.of("Connecting you to ").color(MUTED)
        .append(Text.of(serverDisplay).color(ACCENT).bold())
        .append(Text.of("...").color(MUTED)));
  }

  public static void connected(CommandSource source, String serverDisplay) {
    success(source, "Connected to " + serverDisplay);
  }

  public static void permissionDenied(CommandSource source, String action) {
    source.sendMessage(Text.of("You don't have permission to ").color(BAD)
        .append(Text.of(action).color(TITLE))
        .append(Text.of(".").color(BAD)));
  }

  public static String titleCase(String name) {
    if (name == null || name.isBlank()) return "Unknown";
    if (name.length() == 1) return name.toUpperCase();
    return Character.toUpperCase(name.charAt(0)) + name.substring(1);
  }

  private static String pad(String label, int width) {
    if (label.length() >= width) return label;
    return label + " ".repeat(width - label.length());
  }
}
