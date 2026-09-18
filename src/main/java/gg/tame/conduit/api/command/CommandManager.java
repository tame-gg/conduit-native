package gg.tame.conduit.api.command;

import gg.tame.conduit.api.plugin.Plugin;
import java.util.ArrayList;
import java.util.List;

/**
 * Proxy commands: the ones Conduit answers itself instead of passing to the backend.
 *
 * <p>A player's command reaches a handler on that player's connection thread, after
 * {@code CommandExecuteEvent}; a command a plugin runs through {@link #execute} reaches it on the
 * caller's thread. Handlers must not block: move slow work onto the scheduler.
 */
public interface CommandManager {
  /**
   * Registers {@code command} under its name and every alias, owned by {@code plugin} so that it
   * goes when the plugin is disabled.
   *
   * <p>A name held by one of Conduit's built-in commands (/hub, /server, a /&lt;server&gt; shortcut
   * and so on) is taken over, with a warning in the log, and the built-in comes back when the
   * plugin's command goes. {@code /conduit} is never taken over.
   *
   * @throws IllegalArgumentException when a name or alias belongs to another plugin, is
   *     {@code conduit}, or could never be typed; nothing is registered then
   */
  void register(Plugin plugin, Command command);
  /** Removes the plugin's command by its name or any alias. Another owner's command is left alone. */
  void unregister(Plugin plugin, String name);
  void unregisterAll(Plugin plugin);

  /**
   * Runs {@code line} (with or without its leading slash) as {@code source} would, permission
   * check included. Returns false when no proxy command has that name; the line is not forwarded
   * to any backend. A handler's exception reaches the caller.
   */
  boolean execute(CommandSource source, String line);
  /** What {@code source} would be offered for Tab on {@code line}: command names, then the command's own completer. */
  List<String> complete(CommandSource source, String line);
  /** Whether a proxy command has {@code name} as its name or one of its aliases. */
  boolean hasCommand(String name);

  @FunctionalInterface
  interface Handler {
    void execute(CommandSource source, List<String> arguments);
  }

  /** Suggestions for the argument being typed, which is the last one and may be empty. Conduit filters by what was typed. */
  @FunctionalInterface
  interface Completer {
    List<String> complete(CommandSource source, List<String> arguments);
  }

  /** A command to {@link #register}. An empty permission means everyone may run it. */
  record Command(String name, List<String> aliases, String permission, Handler handler, Completer completer) {
    public Command {
      if (name == null || handler == null || completer == null) throw new IllegalArgumentException("name, handler and completer are required");
      aliases = List.copyOf(aliases == null ? List.of() : aliases);
      permission = permission == null ? "" : permission;
    }
    public static Builder builder(String name) { return new Builder(name); }

    public static final class Builder {
      private final String name;
      private final List<String> aliases = new ArrayList<>();
      private String permission = "";
      private Handler handler = (source, arguments) -> { };
      private Completer completer = (source, arguments) -> List.of();
      private Builder(String name) { this.name = name; }
      public Builder alias(String alias) { aliases.add(alias); return this; }
      public Builder permission(String permission) { this.permission = permission; return this; }
      public Builder handler(Handler handler) { this.handler = handler; return this; }
      public Builder completer(Completer completer) { this.completer = completer; return this; }
      public Command build() { return new Command(name, aliases, permission, handler, completer); }
    }
  }
}
