package gg.tame.conduit.command;

import java.util.List;
import java.util.Locale;

public record RegisteredCommand(String name, List<String> aliases, String permission, CommandExecutor executor, TabCompleter completer) {
  public RegisteredCommand {
    name = name.toLowerCase(Locale.ROOT);
    aliases = aliases.stream().map(alias -> alias.toLowerCase(Locale.ROOT)).toList();
    if (name.isBlank()) throw new IllegalArgumentException("command name is required");
  }
}
