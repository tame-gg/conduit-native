// SPDX-License-Identifier: GPL-3.0-or-later
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
   * <p>A name may itself start with a slash, as WorldEdit's do: a player types {@code //wand} for a
   * command named {@code /wand}. Exactly one slash is taken off what the player types, and the rest
   * must match a name exactly, so {@code //wand} never runs a command named {@code wand}.
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
    /**
     * Called instead, with {@code text} what followed the command's name and one space exactly as
     * typed: {@code arguments} collapses runs of spaces, which a command taking free text may need.
     * Override this one then; by default it runs {@link #execute(CommandSource, List)}.
     */
    default void execute(CommandSource source, List<String> arguments, String text) { execute(source, arguments); }
  }

  /** Suggestions for the argument being typed, which is the last one and may be empty. Conduit filters by what was typed. */
  @FunctionalInterface
  interface Completer {
    List<String> complete(CommandSource source, List<String> arguments);
    /** As {@link Handler#execute(CommandSource, List, String)}: {@code text} is the arguments exactly as typed. */
    default List<String> complete(CommandSource source, List<String> arguments, String text) { return complete(source, arguments); }
  }

  /**
   * Whether a command is there at all for {@code source} typing {@code arguments}. Asked on the
   * thread running the command, before its permission and its handler, so it must answer quickly.
   */
  @FunctionalInterface
  interface Requirement {
    boolean test(CommandSource source, List<String> arguments);
  }

  /**
   * A command to {@link #register}. An empty permission means everyone may run it; a source without
   * it is told so. A {@code requirement} that answers false instead makes the proxy act as if it had
   * no such command for that source: a player's command goes on to their backend, and
   * {@link #execute} returns false. A requirement that throws answers false.
   */
  record Command(String name, List<String> aliases, String permission, Handler handler, Completer completer, Requirement requirement,
                 List<CommandSyntax> syntax) {
    public Command {
      if (name == null || handler == null || completer == null) throw new IllegalArgumentException("name, handler and completer are required");
      aliases = List.copyOf(aliases == null ? List.of() : aliases);
      permission = permission == null ? "" : permission;
      syntax = List.copyOf(syntax == null ? List.of() : syntax);
    }
    /** A command whose shape the client is not told; see {@link CommandSyntax}. */
    public Command(String name, List<String> aliases, String permission, Handler handler, Completer completer, Requirement requirement) {
      this(name, aliases, permission, handler, completer, requirement, List.of());
    }
    /** A command that is there for every source. */
    public Command(String name, List<String> aliases, String permission, Handler handler, Completer completer) {
      this(name, aliases, permission, handler, completer, null);
    }
    public static Builder builder(String name) { return new Builder(name); }

    public static final class Builder {
      private final String name;
      private final List<String> aliases = new ArrayList<>();
      private String permission = "";
      private Handler handler = (source, arguments) -> { };
      private Completer completer = (source, arguments) -> List.of();
      private Requirement requirement;
      private final List<CommandSyntax> syntax = new ArrayList<>();
      private Builder(String name) { this.name = name; }
      public Builder alias(String alias) { aliases.add(alias); return this; }
      public Builder permission(String permission) { this.permission = permission; return this; }
      public Builder handler(Handler handler) { this.handler = handler; return this; }
      public Builder completer(Completer completer) { this.completer = completer; return this; }
      public Builder requires(Requirement requirement) { this.requirement = requirement; return this; }
      /**
       * What follows the command's name, for the tree a 1.13+ client parses against. Left unsaid,
       * the client is given one greedy argument that accepts anything; see {@link CommandSyntax}.
       */
      public Builder syntax(List<CommandSyntax> nodes) { syntax.clear(); if (nodes != null) syntax.addAll(nodes); return this; }
      public Command build() { return new Command(name, aliases, permission, handler, completer, requirement, syntax); }
    }
  }
}
