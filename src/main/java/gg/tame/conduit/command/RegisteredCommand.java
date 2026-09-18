// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.command;

import java.util.List;
import java.util.Locale;

public record RegisteredCommand(String name, List<String> aliases, String permission, CommandExecutor executor, TabCompleter completer) {
  public RegisteredCommand {
    name = name.toLowerCase(Locale.ROOT);
    aliases = aliases.stream().map(alias -> alias.toLowerCase(Locale.ROOT)).toList();
    if (name.isBlank()) throw new IllegalArgumentException("command name is required");
    check(name);
    for (String alias : aliases) check(alias);
  }
  /** A name the dispatcher can never see again is a silent dead command; refuse it at registration. */
  private static void check(String name) {
    if (name.isBlank() || name.startsWith("/") || name.chars().anyMatch(Character::isWhitespace)) {
      throw new IllegalArgumentException("invalid command name: " + name);
    }
  }
}
