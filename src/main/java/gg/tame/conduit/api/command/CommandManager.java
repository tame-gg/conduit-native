package gg.tame.conduit.api.command;

import gg.tame.conduit.api.plugin.Plugin;
import gg.tame.conduit.command.CommandExecutor;
import gg.tame.conduit.command.RegisteredCommand;
import gg.tame.conduit.command.TabCompleter;
import java.util.ArrayList;
import java.util.List;

public interface CommandManager {
  void register(Plugin plugin, RegisteredCommand command);
  void unregister(Plugin plugin, String name);
  void unregisterAll(Plugin plugin);

  final class Command {
    private String name;
    private final List<String> aliases = new ArrayList<>();
    private String permission = "";
    private CommandExecutor executor = (source, arguments) -> {};
    private TabCompleter completer = (source, arguments) -> List.of();
    private Command() {}
    public static Command builder(String name) {
      Command command = new Command();
      command.name = name;
      return command;
    }
    public Command alias(String alias) { aliases.add(alias); return this; }
    public Command permission(String permission) { this.permission = permission; return this; }
    public Command handler(CommandExecutor executor) { this.executor = executor; return this; }
    public Command completer(TabCompleter completer) { this.completer = completer; return this; }
    public RegisteredCommand build() {
      return new RegisteredCommand(name, List.copyOf(aliases), permission, executor, completer);
    }
  }
}
