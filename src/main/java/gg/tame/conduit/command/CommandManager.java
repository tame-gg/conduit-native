package gg.tame.conduit.command;

import gg.tame.conduit.api.plugin.Plugin;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Small native command dispatcher. */
public final class CommandManager implements gg.tame.conduit.api.command.CommandManager {
  private final Map<String, RegisteredCommand> commands = new LinkedHashMap<>();
  private final Map<Plugin, List<RegisteredCommand>> owned = new ConcurrentHashMap<>();
  public synchronized void register(RegisteredCommand command) {
    // Every key is checked before any is installed: a collision on the second alias used to leave
    // the name and the aliases before it registered, so a rejected command was half usable.
    List<String> keys = new ArrayList<>(1 + command.aliases().size());
    keys.add(command.name());
    keys.addAll(command.aliases());
    for (String key : keys) {
      if (commands.containsKey(key)) throw new IllegalArgumentException("command already registered: " + key);
    }
    for (String key : keys) commands.put(key, command);
  }
  @Override public synchronized void register(Plugin plugin, RegisteredCommand command) {
    register(command);
    owned.computeIfAbsent(plugin, ignored -> new ArrayList<>()).add(command);
  }
  /** Removes a command by any of its names; returns it, or null when nothing matched. */
  public synchronized RegisteredCommand unregister(String name) {
    RegisteredCommand command = commands.get(normalize(name));
    if (command == null) return null;
    commands.entrySet().removeIf(entry -> entry.getValue() == command);
    return command;
  }
  @Override public synchronized void unregister(Plugin plugin, String name) {
    List<RegisteredCommand> list = owned.get(plugin);
    RegisteredCommand command = commands.get(normalize(name));
    // Scoped to the caller: one plugin unregistering "server" must not take out another plugin's
    // command or a built-in. Removal from the owned list is by identity, so unregistering by an
    // alias no longer leaves the command behind for unregisterAll to trip over.
    if (command == null || list == null || !list.remove(command)) return;
    unregister(command.name());
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
    if (!permitted(source, command)) {
      Messages.permission(source);
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
    if (!permitted(source, command)) return List.of();
    String prefix = parsed.arguments().isEmpty() ? "" : parsed.arguments().getLast();
    List<String> raw = command.completer().complete(source, parsed.arguments());
    if (prefix.isEmpty()) return raw;
    String needle = prefix.toLowerCase(Locale.ROOT);
    List<String> filtered = new ArrayList<>();
    for (String value : raw) if (value.toLowerCase(Locale.ROOT).startsWith(needle)) filtered.add(value);
    return filtered;
  }
  /** Every name a client can type, names and aliases alike, in registration order. */
  public synchronized List<String> names() { return List.copyOf(new LinkedHashSet<>(commands.keySet())); }
  private static boolean permitted(CommandSource source, RegisteredCommand command) {
    String permission = command.permission();
    return permission == null || permission.isBlank() || source.hasPermission(permission);
  }
  private List<String> prefixes(CommandSource source, String prefix) {
    String needle = prefix.toLowerCase(Locale.ROOT);
    List<String> names = new ArrayList<>();
    synchronized (this) {
      // Keyed, not named: an alias is what the player typed, so completing "/al" to the primary
      // name is right, but "/tp" must offer "tp" and not go looking for a command called "tp".
      for (Map.Entry<String, RegisteredCommand> entry : commands.entrySet()) {
        if (!entry.getKey().startsWith(needle)) continue;
        if (!permitted(source, entry.getValue())) continue;
        if (!names.contains(entry.getKey())) names.add(entry.getKey());
      }
    }
    return names;
  }
  private static String normalize(String name) { return name.toLowerCase(Locale.ROOT); }
  public Optional<RegisteredCommand> get(String name) { synchronized (this) { return Optional.ofNullable(commands.get(normalize(name))); } }
}
