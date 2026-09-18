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
  /** The operator's admin surface: no plugin may take it, or it could lock the operator out. */
  private static final String RESERVED = "conduit";
  private final Map<String, RegisteredCommand> commands = new LinkedHashMap<>();
  private final Map<Plugin, List<RegisteredCommand>> owned = new ConcurrentHashMap<>();
  /** Commands registered with no plugin: Conduit's own, which a plugin may displace. */
  private final java.util.Set<RegisteredCommand> builtIns = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
  /** Built-ins a plugin displaced, by the key it took, to come back when that plugin lets go. */
  private final Map<String, RegisteredCommand> displaced = new LinkedHashMap<>();
  public synchronized void register(RegisteredCommand command) {
    install(command, null);
    builtIns.add(command);
  }
  /**
   * A plugin's command displaces a built-in holding the same name, as on Velocity, where a hub
   * plugin's /hub and /lobby were refused outright because Conduit's /hub and /lobby shortcut held
   * them. Another plugin's name is never displaced, and neither is /conduit.
   */
  public synchronized void register(Plugin plugin, RegisteredCommand command) {
    install(command, plugin);
    owned.computeIfAbsent(plugin, ignored -> new ArrayList<>()).add(command);
  }
  private void install(RegisteredCommand command, Plugin plugin) {
    // Every key is checked before any is installed: a collision on the second alias used to leave
    // the name and the aliases before it registered, so a rejected command was half usable.
    List<String> keys = new ArrayList<>(1 + command.aliases().size());
    keys.add(command.name());
    keys.addAll(command.aliases());
    List<String> taken = new ArrayList<>();
    for (String key : keys) {
      if (plugin != null && key.equals(RESERVED)) throw new IllegalArgumentException("/" + RESERVED + " is reserved for the proxy operator");
      RegisteredCommand holder = commands.get(key);
      if (holder == null || holder == command) continue;
      if (plugin == null || !builtIns.contains(holder)) throw new IllegalArgumentException("command already registered: " + key);
      taken.add(key);
    }
    for (String key : taken) displaced.put(key, commands.get(key));
    for (String key : keys) commands.put(key, command);
    if (!taken.isEmpty()) {
      gg.tame.conduit.log.ConduitLog.warn("Plugin " + plugin.description().id() + " replaced Conduit's built-in "
          + String.join(", ", taken.stream().map(key -> "/" + key).toList()));
    }
  }
  /** Built-in names a plugin currently holds, so the client graph declares the plugin's shape for them. */
  public synchronized java.util.Set<String> displacedBuiltIns() { return java.util.Set.copyOf(displaced.keySet()); }
  @Override public void register(Plugin plugin, gg.tame.conduit.api.command.CommandManager.Command command) {
    register(plugin, new RegisteredCommand(command.name(), command.aliases(), command.permission(),
        (source, arguments) -> command.handler().execute(external(source), arguments),
        (source, arguments) -> command.completer().complete(external(source), arguments)));
  }
  /** Removes a command by any of its names; returns it, or null when nothing matched. */
  public synchronized RegisteredCommand unregister(String name) {
    RegisteredCommand command = commands.get(normalize(name));
    if (command == null) return null;
    commands.entrySet().removeIf(entry -> entry.getValue() == command);
    builtIns.remove(command);
    // A built-in this command displaced gets its name back, unless something has taken it since.
    for (var entry : new ArrayList<>(displaced.entrySet())) {
      if (commands.containsKey(entry.getKey())) continue;
      commands.put(entry.getKey(), entry.getValue());
      displaced.remove(entry.getKey());
    }
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
  @Override public boolean execute(gg.tame.conduit.api.command.CommandSource source, String line) {
    return dispatch(internal(source), line);
  }
  @Override public List<String> complete(gg.tame.conduit.api.command.CommandSource source, String line) {
    return tabComplete(internal(source), line);
  }
  @Override public boolean hasCommand(String name) { return name != null && get(name).isPresent(); }
  /** A source from outside core, given the one thing built-in commands ask that the API does not carry. */
  private static CommandSource internal(gg.tame.conduit.api.command.CommandSource source) {
    return source instanceof CommandSource own ? own : new Foreign(source);
  }
  /** The caller's own object again, so a plugin that ran a command as its source gets that source back. */
  private static gg.tame.conduit.api.command.CommandSource external(CommandSource source) {
    return source instanceof Foreign foreign ? foreign.source : source;
  }
  private record Foreign(gg.tame.conduit.api.command.CommandSource source) implements CommandSource {
    @Override public String username() { return source.username(); }
    @Override public boolean hasPermission(String permission) { return source.hasPermission(permission); }
    @Override public void sendMessage(String message) { source.sendMessage(message); }
    @Override public void sendMessage(gg.tame.conduit.api.text.Text text) { source.sendMessage(text); }
    @Override public String currentBackend() {
      return source instanceof gg.tame.conduit.api.player.Player player ? player.currentServer().name() : "";
    }
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
