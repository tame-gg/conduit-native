package gg.tame.conduit.compat.velocity;

import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.tree.CommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.Command;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import gg.tame.conduit.command.RegisteredCommand;
import gg.tame.conduit.runtime.ConduitRuntime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

final class VelocityCommandHost implements CommandManager {
  private final ConduitRuntime runtime;
  private final VelocityEnvironment environment;
  private final ConcurrentHashMap<String, CommandMeta> metas = new ConcurrentHashMap<>();
  VelocityCommandHost(ConduitRuntime runtime, VelocityEnvironment environment) {
    this.runtime = runtime;
    this.environment = environment;
  }
  @Override public CommandMeta.Builder metaBuilder(String alias) { return new MetaBuilder(alias); }
  @Override public CommandMeta.Builder metaBuilder(BrigadierCommand command) {
    return UnsupportedApis.unsupported("CommandManager.metaBuilder(BrigadierCommand)");
  }
  @Override public void register(BrigadierCommand command) {
    UnsupportedApis.unsupported("BrigadierCommand");
  }
  @Override public void register(CommandMeta meta, Command command) {
    if (!(command instanceof SimpleCommand simple)) {
      UnsupportedApis.unsupported("non-SimpleCommand registration");
      return;
    }
    String name = meta.getAliases().iterator().next();
    List<String> aliases = new ArrayList<>(meta.getAliases());
    aliases.remove(name);
    runtime.commandManager().register(new RegisteredCommand(name, aliases, "", (source, arguments) -> {
      CommandSource velocitySource = velocitySource(source);
      simple.execute(new Invocation(velocitySource, name, arguments.toArray(String[]::new)));
    }, (source, arguments) -> simple.suggest(new Invocation(velocitySource(source), name, arguments.toArray(String[]::new)))));
    for (String alias : meta.getAliases()) metas.put(alias.toLowerCase(Locale.ROOT), meta);
  }
  private CommandSource velocitySource(gg.tame.conduit.command.CommandSource source) {
    if (source instanceof gg.tame.conduit.api.player.Player player) return environment.wrap(player);
    return environment.proxy().getConsoleCommandSource();
  }
  @Override public void unregister(String alias) {
    runtime.commandManager().unregister(alias);
    metas.remove(alias.toLowerCase(Locale.ROOT));
  }
  @Override public void unregister(CommandMeta meta) {
    for (String alias : meta.getAliases()) unregister(alias);
  }
  @Override public CommandMeta getCommandMeta(String alias) { return metas.get(alias.toLowerCase(Locale.ROOT)); }
  @Override public CompletableFuture<Boolean> executeAsync(CommandSource source, String cmdLine) {
    if (source instanceof VelocityPlayer player && player.nativePlayer() instanceof gg.tame.conduit.command.CommandSource commandSource) {
      return CompletableFuture.completedFuture(runtime.commandManager().dispatch(commandSource, cmdLine));
    }
    return CompletableFuture.completedFuture(false);
  }
  @Override public CompletableFuture<Boolean> executeImmediatelyAsync(CommandSource source, String cmdLine) {
    return executeAsync(source, cmdLine);
  }
  @Override public CompletableFuture<List<String>> offerSuggestions(CommandSource source, String line) {
    if (source instanceof VelocityPlayer player && player.nativePlayer() instanceof gg.tame.conduit.command.CommandSource commandSource) {
      return CompletableFuture.completedFuture(runtime.commandManager().tabComplete(commandSource, line));
    }
    return CompletableFuture.completedFuture(List.of());
  }
  @Override public CompletableFuture<Suggestions> offerBrigadierSuggestions(CommandSource source, String line) {
    return UnsupportedApis.unsupported("offerBrigadierSuggestions");
  }
  @Override public Collection<String> getAliases() { return List.copyOf(metas.keySet()); }
  @Override public boolean hasCommand(String alias) { return metas.containsKey(alias.toLowerCase(Locale.ROOT)); }
  @Override public boolean hasCommand(String alias, CommandSource source) { return hasCommand(alias); }

  private record Invocation(CommandSource source, String alias, String[] arguments) implements SimpleCommand.Invocation {}

  private static final class MetaBuilder implements CommandMeta.Builder {
    private final List<String> aliases = new ArrayList<>();
    private Object plugin;
    private MetaBuilder(String alias) { aliases.add(alias); }
    @Override public CommandMeta.Builder aliases(String... aliases) {
      this.aliases.addAll(List.of(aliases));
      return this;
    }
    @Override public CommandMeta.Builder hint(CommandNode<CommandSource> node) { return this; }
    @Override public CommandMeta.Builder plugin(Object plugin) { this.plugin = plugin; return this; }
    @Override public CommandMeta build() { return new Meta(List.copyOf(aliases), plugin); }
  }
  private record Meta(Collection<String> aliases, Object plugin) implements CommandMeta {
    @Override public Collection<String> getAliases() { return aliases; }
    @Override public Collection<CommandNode<CommandSource>> getHints() { return List.of(); }
    @Override public Object getPlugin() { return plugin; }
  }
}
