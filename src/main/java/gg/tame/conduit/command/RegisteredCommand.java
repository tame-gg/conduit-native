// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.command;

import java.util.List;
import java.util.Locale;

/** {@code requirement}, when there is one, decides whether the command is there at all for a source; see the API's Command. */
public record RegisteredCommand(String name, List<String> aliases, String permission, CommandExecutor executor, TabCompleter completer,
                                java.util.function.BiPredicate<CommandSource, List<String>> requirement) {
  public RegisteredCommand(String name, List<String> aliases, String permission, CommandExecutor executor, TabCompleter completer) {
    this(name, aliases, permission, executor, completer, null);
  }
  public RegisteredCommand {
    name = name.toLowerCase(Locale.ROOT);
    aliases = aliases.stream().map(alias -> alias.toLowerCase(Locale.ROOT)).toList();
    if (name.isBlank()) throw new IllegalArgumentException("command name is required");
    check(name);
    for (String alias : aliases) check(alias);
  }
  /**
   * A name the dispatcher can never see again is a silent dead command; refuse it at registration.
   * A leading slash is typeable: the player adds the command's own ("//lpv" is "/lpv"). Slashes
   * alone are not, since a line of nothing but slashes is read as no command at all.
   */
  private static void check(String name) {
    if (name.isBlank() || name.chars().allMatch(c -> c == '/') || name.chars().anyMatch(Character::isWhitespace)) {
      throw new IllegalArgumentException("invalid command name: " + name);
    }
  }
}
