// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.compat.velocity;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.Command;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandResult;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.RawCommand;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.command.PostCommandInvocationEvent;
import gg.tame.conduit.api.plugin.Plugin;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Velocity's CommandManager on Conduit's. Each alias is its own native command, so the handler knows
 * which alias was typed. Bodies run on the adapter's threads, never the player's connection thread.
 *
 * <p>BrigadierCommand trees are parsed, permission-checked, executed and completed here with the
 * Brigadier library against the full command line, and the tree itself is declared to clients --
 * see {@link VelocityCommandSyntax}. A SimpleCommand or a RawCommand says nothing about its own
 * shape, so it gets the literal and one greedy {@code ask_server} argument Velocity declares for
 * those, which is what {@code CommandGraphs#addLiteral} gives every command that declares nothing,
 * plus any hint nodes its CommandMeta carries.
 */
final class VelocityCommandHost implements CommandManager {
  private static final long SUGGEST_WAIT_MS = 3_000;
  /** Set while executeAsync runs a line, so the command body runs inline and the future means "done". */
  private static final ThreadLocal<Boolean> INLINE = ThreadLocal.withInitial(() -> false);
  private final VelocityEnvironment environment;
  private final ConcurrentHashMap<String, Registration> byAlias = new ConcurrentHashMap<>();
  VelocityCommandHost(VelocityEnvironment environment) { this.environment = environment; }

  private record Registration(CommandMeta meta, Command command, Plugin owner, VelocityPluginHost.Container plugin) {}

  @Override public CommandMeta.Builder metaBuilder(String alias) { return new MetaBuilder(alias); }
  @Override public CommandMeta.Builder metaBuilder(BrigadierCommand command) { return new MetaBuilder(command.getNode().getName()); }
  @Override public void register(BrigadierCommand command) { register(metaBuilder(command).build(), command); }

  @Override public synchronized void register(CommandMeta meta, Command command) {
    if (meta == null || command == null) throw new IllegalArgumentException("meta and command are required");
    if (!(command instanceof SimpleCommand || command instanceof RawCommand || command instanceof BrigadierCommand)) {
      throw new IllegalArgumentException(command.getClass().getName() + " must implement SimpleCommand, RawCommand or be a BrigadierCommand");
    }
    VelocityPluginHost.Container plugin = owner(meta, command);
    Plugin owner = plugin == null ? environment.owner : plugin.handle;
    Registration registration = new Registration(meta, command, owner, plugin);
    List<String> done = new ArrayList<>();
    List<String> untypeable = new ArrayList<>();
    try {
      for (String raw : meta.getAliases()) {
        String alias = raw.toLowerCase(Locale.ROOT);
        // A leading slash is fine: "//lpv" asks for an alias "/lpv", as LuckPerms registers one.
        // Conduit's command line splits on spaces, though, so an alias with one could never be typed,
        // and nor could one of slashes alone.
        if (alias.chars().anyMatch(Character::isWhitespace) || alias.chars().allMatch(c -> c == '/')) {
          untypeable.add(alias);
          continue;
        }
        Registration existing = byAlias.get(alias);
        if (existing != null) {
          // A plugin may register its own alias again (mclo.gs registers each Brigadier subcommand
          // under one meta), and the newer command replaces its older one. Anyone else's is refused.
          if (existing.plugin == null || existing.plugin != plugin) throw new IllegalArgumentException("command alias already registered: " + alias);
          forgetAlias(alias);
        }
        environment.conduit.commands().register(owner, gg.tame.conduit.api.command.CommandManager.Command.builder(alias)
            .handler(new gg.tame.conduit.api.command.CommandManager.Handler() {
              @Override public void execute(gg.tame.conduit.api.command.CommandSource source, List<String> arguments) {
                run(registration, alias, source, arguments, String.join(" ", arguments));
              }
              // The text as typed: a RawCommand is owed its runs of spaces, which the list collapses.
              @Override public void execute(gg.tame.conduit.api.command.CommandSource source, List<String> arguments, String text) {
                run(registration, alias, source, arguments, text);
              }
            })
            .completer(new gg.tame.conduit.api.command.CommandManager.Completer() {
              @Override public List<String> complete(gg.tame.conduit.api.command.CommandSource source, List<String> arguments) {
                return suggest(registration, alias, source, arguments, String.join(" ", arguments));
              }
              @Override public List<String> complete(gg.tame.conduit.api.command.CommandSource source, List<String> arguments, String text) {
                return suggest(registration, alias, source, arguments, text);
              }
            })
            .requires((source, arguments) -> available(registration, alias, source, arguments))
            // A BrigadierCommand has already said what it takes, so the client is told that rather
            // than the greedy argument a SimpleCommand or a RawCommand can only be given.
            .syntax(command instanceof BrigadierCommand brigadier
                ? VelocityCommandSyntax.of(brigadier.getNode()) : VelocityCommandSyntax.hinted(meta.getHints()))
            .build());
        byAlias.put(alias, registration);
        done.add(alias);
      }
    } catch (RuntimeException rejected) {
      for (String alias : done) forgetAlias(alias);
      throw rejected;
    }
    if (!untypeable.isEmpty()) {
      environment.log.info("Velocity command aliases " + untypeable + " cannot be typed on Conduit and were not registered");
    }
  }
  /** The plugin in the meta; failing that, the plugin whose jar the command's code came from. */
  private VelocityPluginHost.Container owner(CommandMeta meta, Command command) {
    if (meta.getPlugin() != null) return environment.plugins.require(meta.getPlugin());
    return environment.plugins.owning(command instanceof BrigadierCommand brigadier ? executor(brigadier.getNode()) : command);
  }
  private static Object executor(CommandNode<CommandSource> node) {
    if (node.getCommand() != null) return node.getCommand();
    for (CommandNode<CommandSource> child : node.getChildren()) {
      Object found = executor(child);
      if (found != null) return found;
    }
    return null;
  }

  @Override public synchronized void unregister(String alias) {
    if (alias != null) forgetAlias(alias.toLowerCase(Locale.ROOT));
  }
  @Override public synchronized void unregister(CommandMeta meta) {
    for (var entry : List.copyOf(byAlias.entrySet())) if (entry.getValue().meta == meta) forgetAlias(entry.getKey());
  }
  /** A disabled plugin's native commands are already gone; this drops the Velocity side. */
  synchronized void forget(VelocityPluginHost.Container plugin) {
    for (var entry : List.copyOf(byAlias.entrySet())) if (entry.getValue().plugin == plugin) forgetAlias(entry.getKey());
  }
  private void forgetAlias(String alias) {
    Registration registration = byAlias.remove(alias);
    if (registration != null) environment.conduit.commands().unregister(registration.owner, alias);
  }

  @Override public CommandMeta getCommandMeta(String alias) {
    Registration registration = alias == null ? null : byAlias.get(alias.toLowerCase(Locale.ROOT));
    return registration == null ? null : registration.meta;
  }
  @Override public Collection<String> getAliases() { return List.copyOf(byAlias.keySet()); }
  /** Any proxy command counts, Conduit's own and native plugins' included, as Velocity's built-ins do there. */
  @Override public boolean hasCommand(String alias) { return alias != null && environment.conduit.commands().hasCommand(alias); }
  /** A Velocity command asks its own hasPermission; for any other proxy command there is nothing to ask. */
  @Override public boolean hasCommand(String alias, CommandSource source) {
    Registration registration = alias == null ? null : byAlias.get(alias.toLowerCase(Locale.ROOT));
    if (registration == null) return hasCommand(alias);
    return permitted(registration, alias.toLowerCase(Locale.ROOT), source, new String[0], "");
  }

  @Override public CompletableFuture<Boolean> executeAsync(CommandSource source, String cmdLine) { return execute(source, cmdLine, true); }
  @Override public CompletableFuture<Boolean> executeImmediatelyAsync(CommandSource source, String cmdLine) { return execute(source, cmdLine, false); }
  private CompletableFuture<Boolean> execute(CommandSource source, String cmdLine, boolean fireEvent) {
    gg.tame.conduit.api.command.CommandSource conduitSource = nativeSource(source);
    String line = cmdLine.startsWith("/") ? cmdLine.substring(1) : cmdLine;
    return CompletableFuture.supplyAsync(() -> {
      String command = line;
      if (fireEvent) {
        CommandExecuteEvent event = environment.fireAndWait(new CommandExecuteEvent(source, command));
        if (!event.getResult().isAllowed()) return false;
        if (event.getResult().getCommand().isPresent()) command = event.getResult().getCommand().get();
      }
      INLINE.set(true);
      try { return environment.conduit.commands().execute(conduitSource, command); }
      finally { INLINE.set(false); }
    }, environment.work);
  }
  @Override public CompletableFuture<List<String>> offerSuggestions(CommandSource source, String cmdLine) {
    gg.tame.conduit.api.command.CommandSource conduitSource = nativeSource(source);
    return CompletableFuture.supplyAsync(() -> {
      INLINE.set(true);
      try { return environment.conduit.commands().complete(conduitSource, cmdLine); }
      finally { INLINE.set(false); }
    }, environment.work);
  }
  /** What {@link #offerSuggestions} finds, as replacements for the word being typed. */
  @Override public CompletableFuture<Suggestions> offerBrigadierSuggestions(CommandSource source, String cmdLine) {
    int start = cmdLine.lastIndexOf(' ') + 1;
    return offerSuggestions(source, cmdLine).thenApply(found -> {
      SuggestionsBuilder builder = new SuggestionsBuilder(cmdLine, start);
      for (String text : found) builder.suggest(text);
      return builder.build();
    });
  }

  private gg.tame.conduit.api.command.CommandSource nativeSource(CommandSource source) {
    if (source instanceof VelocityPlayer player) return player.nativePlayer();
    if (source instanceof VelocityConsole) return environment.conduit.console();
    return new PluginSource(source);
  }
  private CommandSource velocitySource(gg.tame.conduit.api.command.CommandSource source) {
    if (source instanceof gg.tame.conduit.api.player.Player player) return environment.player(player);
    if (source instanceof PluginSource plugin) return plugin.velocity();
    return environment.console;
  }

  /**
   * A command source a plugin made itself -- a chat bridge's, a scheduled job's -- as a source Conduit
   * can run a command as. These used to be refused outright. Replies and permission checks go to
   * the plugin's own object, and a Velocity command's body is handed that object back, so whoever
   * ran the command sees its output where they expect it.
   */
  private record PluginSource(CommandSource velocity) implements gg.tame.conduit.api.command.CommandSource {
    @Override public String username() { return velocity.getClass().getSimpleName(); }
    @Override public void sendMessage(String message) { velocity.sendMessage(Component.text(message)); }
    @Override public void sendMessage(gg.tame.conduit.api.text.Text text) { velocity.sendMessage(Texts.toAdventure(text)); }
    @Override public boolean hasPermission(String permission) { return velocity.hasPermission(permission); }
    @Override public Boolean permissionValue(String permission) {
      com.velocitypowered.api.permission.Tristate value = velocity.getPermissionValue(permission);
      return value == com.velocitypowered.api.permission.Tristate.UNDEFINED ? null : value.asBoolean();
    }
  }

  /**
   * Whether the command {@code line} names is a Velocity plugin's. Such a command's body runs here,
   * often after Conduit's handler has returned, so its PostCommandInvocationEvent is fired here too,
   * once the body is done and its outcome known.
   */
  boolean owns(String line) {
    int space = line.indexOf(' ');
    return byAlias.containsKey((space < 0 ? line : line.substring(0, space)).toLowerCase(Locale.ROOT));
  }

  private void run(Registration registration, String alias, gg.tame.conduit.api.command.CommandSource conduitSource, List<String> arguments, String text) {
    CommandSource source = velocitySource(conduitSource);
    Runnable body = () -> {
      CommandResult result = CommandResult.EXECUTED;
      try {
        String[] split = arguments.toArray(String[]::new);
        String raw = text;
        switch (registration.command) {
          case SimpleCommand simple -> simple.execute(new SimpleInvocation(source, alias, split));
          case RawCommand rawCommand -> rawCommand.execute(new RawInvocation(source, alias, raw));
          case BrigadierCommand brigadier -> {
            CommandDispatcher<CommandSource> dispatcher = dispatcher(brigadier, alias);
            try { dispatcher.execute(dispatcher.parse(line(alias, raw), source)); }
            catch (CommandSyntaxException syntax) {
              result = CommandResult.SYNTAX_ERROR;
              source.sendMessage(Component.text(syntax.getMessage(), NamedTextColor.RED));
            }
          }
          default -> throw new IllegalStateException("unreachable");
        }
      } catch (RuntimeException | LinkageError failed) {
        result = CommandResult.EXCEPTION;
        environment.log.log(Level.SEVERE, "Velocity command /" + alias + " failed", failed);
        source.sendMessage(Component.text("An internal error occurred while running this command.", NamedTextColor.RED));
      }
      if (environment.events.listening(PostCommandInvocationEvent.class)) {
        environment.events.fire(new PostCommandInvocationEvent(source, line(alias, text), result));
      }
    };
    if (INLINE.get()) body.run();
    else environment.work.execute(body);
  }

  private List<String> suggest(Registration registration, String alias, gg.tame.conduit.api.command.CommandSource conduitSource, List<String> arguments, String text) {
    CommandSource source = velocitySource(conduitSource);
    String[] split = arguments.toArray(String[]::new);
    String raw = text;
    CompletableFuture<List<String>> suggestions = CompletableFuture.supplyAsync(() -> {
      if (!permitted(registration, alias, source, split, raw)) return CompletableFuture.completedFuture(List.<String>of());
      return switch (registration.command) {
        case SimpleCommand simple -> simple.suggestAsync(new SimpleInvocation(source, alias, split));
        case RawCommand rawCommand -> rawCommand.suggestAsync(new RawInvocation(source, alias, raw));
        case BrigadierCommand brigadier -> {
          CommandDispatcher<CommandSource> dispatcher = dispatcher(brigadier, alias);
          yield dispatcher.getCompletionSuggestions(dispatcher.parse(line(alias, raw), source))
              .thenApply(found -> found.getList().stream().map(Suggestion::getText).toList());
        }
        default -> CompletableFuture.completedFuture(List.<String>of());
      };
    }, environment.work).thenCompose(future -> future);
    if (INLINE.get()) return suggestions.join();
    try {
      List<String> result = suggestions.get(SUGGEST_WAIT_MS, TimeUnit.MILLISECONDS);
      return result == null ? List.of() : result;
    } catch (Exception slowOrFailed) {
      environment.log.log(Level.WARNING, "Velocity command /" + alias + " gave no suggestions", slowOrFailed);
      return List.of();
    }
  }

  /**
   * The command's hasPermission, as Conduit's requirement for it: one that says no leaves the
   * command to the backend, as on Velocity. Plugin code, so it runs on the adapter's threads, and the
   * connection thread waits for it as long as for any other plugin answer.
   */
  private boolean available(Registration registration, String alias, gg.tame.conduit.api.command.CommandSource conduitSource, List<String> arguments) {
    CommandSource source = velocitySource(conduitSource);
    String[] split = arguments.toArray(String[]::new);
    String raw = String.join(" ", arguments);
    if (INLINE.get()) return permitted(registration, alias, source, split, raw);
    CompletableFuture<Boolean> answer = CompletableFuture.supplyAsync(() -> permitted(registration, alias, source, split, raw), environment.work);
    return environment.await(answer, "hasPermission of /" + alias) && answer.join();
  }
  private boolean permitted(Registration registration, String alias, CommandSource source, String[] split, String raw) {
    return switch (registration.command) {
      case SimpleCommand simple -> simple.hasPermission(new SimpleInvocation(source, alias, split));
      case RawCommand rawCommand -> rawCommand.hasPermission(new RawInvocation(source, alias, raw));
      case BrigadierCommand brigadier -> brigadier.getNode().canUse(source);
      default -> false;
    };
  }
  /** The command's tree under the alias that was typed: another alias gets a copy of the root literal. */
  private static CommandDispatcher<CommandSource> dispatcher(BrigadierCommand command, String alias) {
    CommandDispatcher<CommandSource> dispatcher = new CommandDispatcher<>();
    LiteralCommandNode<CommandSource> node = command.getNode();
    if (node.getName().equalsIgnoreCase(alias)) {
      dispatcher.getRoot().addChild(node);
    } else {
      LiteralCommandNode<CommandSource> copy = new LiteralCommandNode<>(alias, node.getCommand(), node.getRequirement(),
          node.getRedirect(), node.getRedirectModifier(), node.isFork());
      for (CommandNode<CommandSource> child : node.getChildren()) copy.addChild(child);
      dispatcher.getRoot().addChild(copy);
    }
    return dispatcher;
  }
  private static String line(String alias, String raw) { return raw.isEmpty() ? alias : alias + " " + raw; }

  private record SimpleInvocation(CommandSource source, String alias, String[] arguments) implements SimpleCommand.Invocation {}
  private record RawInvocation(CommandSource source, String alias, String arguments) implements RawCommand.Invocation {}

  private static final class MetaBuilder implements CommandMeta.Builder {
    private final List<String> aliases = new ArrayList<>();
    private final List<CommandNode<CommandSource>> hints = new ArrayList<>();
    private Object plugin;
    private MetaBuilder(String alias) { add(alias); }
    private void add(String alias) {
      if (alias == null || alias.isBlank()) throw new IllegalArgumentException("alias is required");
      String lower = alias.toLowerCase(Locale.ROOT);
      if (!aliases.contains(lower)) aliases.add(lower);
    }
    @Override public CommandMeta.Builder aliases(String... more) { for (String alias : more) add(alias); return this; }
    /** As the API documents: a hint describes arguments, so it may neither run anything nor redirect. */
    @Override public CommandMeta.Builder hint(CommandNode<CommandSource> node) {
      if (node == null) throw new IllegalArgumentException("hint node is required");
      if (node.getCommand() != null) throw new IllegalArgumentException("a hint node may not be executable");
      if (node.getRedirect() != null) throw new IllegalArgumentException("a hint node may not redirect");
      hints.add(node);
      return this;
    }
    @Override public CommandMeta.Builder plugin(Object plugin) { this.plugin = plugin; return this; }
    @Override public CommandMeta build() { return new Meta(List.copyOf(aliases), List.copyOf(hints), plugin); }
  }
  private record Meta(Collection<String> aliases, Collection<CommandNode<CommandSource>> hints, Object plugin) implements CommandMeta {
    @Override public Collection<String> getAliases() { return aliases; }
    @Override public Collection<CommandNode<CommandSource>> getHints() { return hints; }
    @Override public Object getPlugin() { return plugin; }
  }
}
