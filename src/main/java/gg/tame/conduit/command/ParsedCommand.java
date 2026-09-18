// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record ParsedCommand(String name, List<String> arguments) {
  public static ParsedCommand parse(String line) { return parseInternal(line, false); }
  public static ParsedCommand parseKeepEmpty(String line) { return parseInternal(line, true); }
  /**
   * What follows the command's name and the one space after it, exactly as typed: the arguments
   * list collapses runs of spaces, and a command that takes free text needs them as they were.
   */
  public static String argumentText(String line) {
    if (line == null) return "";
    String rest = line.stripLeading();
    if (rest.startsWith("/")) rest = rest.substring(1);
    int space = rest.indexOf(' ');
    return space < 0 ? "" : rest.substring(space + 1);
  }
  private static ParsedCommand parseInternal(String line, boolean keepTrailing) {
    if (line == null) return new ParsedCommand("", List.of());
    String trimmed = keepTrailing ? line.stripLeading() : line.strip();
    if (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
    if (!keepTrailing) trimmed = trimmed.strip();
    if (trimmed.isEmpty() || trimmed.equals("/")) return new ParsedCommand("", List.of());
    String[] parts = trimmed.split(" ", -1);
    String name = parts[0].toLowerCase(Locale.ROOT);
    List<String> arguments = new ArrayList<>();
    for (int index = 1; index < parts.length; index++) {
      // Runs of spaces are collapsed either way. Only the trailing empty token is real: it is how
      // "/send lobby " says the player has started a second argument. "/send  lobby" is one.
      if (parts[index].isEmpty() && !(keepTrailing && index == parts.length - 1)) continue;
      arguments.add(parts[index]);
    }
    return new ParsedCommand(name, List.copyOf(arguments));
  }
}
