package gg.tame.conduit.command;

import gg.tame.conduit.api.plugin.Plugin;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Small native command dispatcher. Not a Velocity clone. */
public final class CommandManager implements gg.tame.conduit.api.command.CommandManager {
  private final Map<String, RegisteredCommand> commands = new LinkedHashMap<>();
  private final Map<Plugin, List<RegisteredCommand>> owned = new ConcurrentHashMap<>();
  public synchronized void register(RegisteredCommand command) {
    put(command.name(), command);
    for (String alias : command.aliases()) put(alias, command);
  }
  @Override public synchronized void register(Plugin plugin, RegisteredCommand command) {
    register(command);
    owned.computeIfAbsent(plugin, ignored -> new ArrayList<>()).add(command);
  }
  public synchronized void unregister(String name) {
    RegisteredCommand command = commands.get(normalize(name));
    if (command == null) return;
    commands.entrySet().removeIf(entry -> entry.getValue() == command);
  }
  @Override public synchronized void unregister(Plugin plugin, String name) {
    unregister(name);
    List<RegisteredCommand> list = owned.get(plugin);
    if (list != null) list.removeIf(command -> command.name().equals(normalize(name)));
  }
  @Override public synchronized void unregisterAll(Plugin plugin) {
    List<RegisteredCommand> list = owned.remove(plugin);
    if (list == null) return;
    for (RegisteredCommand command : new ArrayList<>(list)) unregister(command.name());
  }
  public boolean dispatch(CommandSource source, String line) {
    ParsedCommand parsed = ParsedCommand.parse(line);
    if (parsed.name().isEmpty()) return false;
    RegisteredCommand command;
    synchronized (this) { command = commands.get(parsed.name()); }
    if (command == null) return false;
    if (command.permission() != null && !command.permission().isBlank() && !source.hasPermission(command.permission())) {
      source.sendMessage("You do not have permission to do that.");
      return true;
    }
    command.executor().execute(source, parsed.arguments());
    return true;
  }
  public List<String> tabComplete(CommandSource source, String line) {
    ParsedCommand parsed = ParsedCommand.parseKeepEmpty(line);
    if (parsed.name().isEmpty() && parsed.arguments().isEmpty()) {
      return prefixes(source, "");
    }
    if (parsed.arguments().isEmpty() && !line.endsWith(" ")) {
      return prefixes(source, parsed.name());
    }
    RegisteredCommand command;
    synchronized (this) { command = commands.get(parsed.name()); }
    if (command == null || command.completer() == null) return List.of();
    if (command.permission() != null && !command.permission().isBlank() && !source.hasPermission(command.permission())) return List.of();
    String prefix = parsed.arguments().isEmpty() ? "" : parsed.arguments().getLast();
    List<String> raw = command.completer().complete(source, parsed.arguments());
    if (prefix.isEmpty()) return raw;
    String needle = prefix.toLowerCase(Locale.ROOT);
    List<String> filtered = new ArrayList<>();
    for (String value : raw) if (value.toLowerCase(Locale.ROOT).startsWith(needle)) filtered.add(value);
    return filtered;
  }
  private void put(String key, RegisteredCommand command) {
    if (commands.putIfAbsent(key, command) != null) throw new IllegalArgumentException("command already registered: " + key);
  }
  private List<String> prefixes(CommandSource source, String prefix) {
    String needle = prefix.toLowerCase(Locale.ROOT);
    List<String> names = new ArrayList<>();
    for (RegisteredCommand command : new LinkedHashMap<>(commands).values()) {
      if (names.contains(command.name())) continue;
      if (command.permission() != null && !command.permission().isBlank() && !source.hasPermission(command.permission())) continue;
      if (command.name().startsWith(needle)) names.add(command.name());
    }
    return names;
  }
  private static String normalize(String name) { return name.toLowerCase(Locale.ROOT); }
  public Optional<RegisteredCommand> get(String name) { synchronized (this) { return Optional.ofNullable(commands.get(normalize(name))); } }
}
