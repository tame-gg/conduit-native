// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.permission;

import gg.tame.conduit.api.permission.PermissionSubject;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.config.PermissionSettings;
import gg.tame.conduit.log.ConduitLog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Permissions from {@code permissions.toml} beside the configuration, for a network too small to
 * want a permissions plugin and too big for one operators list.
 *
 * <pre>
 * [groups.default]
 * permissions = ["conduit.command.find"]
 *
 * [groups.mod]
 * inherits = ["default"]
 * permissions = ["conduit.command.gkick", "conduit.notify.moderation", "-conduit.command.gban"]
 *
 * [users]
 * "Steve" = ["mod"]
 * "069a79f4-44e9-4726-a5be-fca90e38aaf5" = ["mod", "builder"]
 * </pre>
 *
 * <p>Everyone is in {@code default}. A node starting with {@code -} is denied outright, which beats
 * a grant from any other group. {@code conduit.admin} stands for every {@code conduit.} node, as it
 * does for a plugin's provider. Users are names or UUIDs; in offline mode a name is what a client
 * claims, so there a UUID is the safer key. With no file, the operators list in {@code conduit.toml}
 * answers as before.
 *
 * <p>Read at start and on {@code /conduit reload}; a line that cannot be read is a warning and the
 * previous file stays in force.
 */
public final class FilePermissionProvider extends DefaultPermissionProvider {
  public static final String FILE = "permissions.toml";
  private static final String DEFAULT_GROUP = "default";

  private record Group(List<String> inherits, Set<String> granted, Set<String> denied) {}
  private record Loaded(Map<String, Group> groups, Map<String, List<String>> users) {}

  private volatile Loaded loaded = new Loaded(Map.of(), Map.of());
  private volatile Path file;

  public FilePermissionProvider(Supplier<PermissionSettings> operators) { super(operators); }

  /** Reads {@code permissions.toml} in {@code configDirectory}, or forgets any earlier file if it is gone. */
  public void reload(Path configDirectory) {
    Path candidate = configDirectory.resolve(FILE);
    this.file = candidate;
    if (!Files.isRegularFile(candidate)) { loaded = new Loaded(Map.of(), Map.of()); return; }
    try {
      loaded = parse(Files.readAllLines(candidate, StandardCharsets.UTF_8));
      ConduitLog.info("Permissions: " + loaded.groups().size() + " group(s) and " + loaded.users().size()
          + " user(s) from " + FILE + ".");
    } catch (IOException | IllegalArgumentException bad) {
      ConduitLog.warn("Could not read " + candidate + ", so the permissions in force are unchanged: " + bad.getMessage());
    }
  }

  /** Whether a file was found, so the doctor and the log can say which provider answers. */
  public boolean active() { return !loaded.groups().isEmpty() || !loaded.users().isEmpty(); }

  @Override public boolean hasPermission(PermissionSubject subject, String permission) {
    Boolean explicit = permissionValue(subject, permission);
    return explicit != null && explicit;
  }

  @Override public Boolean permissionValue(PermissionSubject subject, String permission) {
    if (permission == null || !(subject instanceof Player player)) return null;
    Loaded current = loaded;
    // No file: the operators list, as the default provider always answered.
    if (current.groups().isEmpty() && current.users().isEmpty()) return super.hasPermission(player, permission) ? Boolean.TRUE : null;
    Set<String> granted = new HashSet<>();
    Set<String> denied = new HashSet<>();
    Set<String> visited = new HashSet<>();
    for (String group : groupsOf(current, player)) collect(current, group, granted, denied, visited);
    String node = permission.toLowerCase(Locale.ROOT);
    if (denied.contains(node)) return Boolean.FALSE;
    if (granted.contains(node)) return Boolean.TRUE;
    if (node.startsWith("conduit.") && granted.contains("conduit.admin") && !denied.contains("conduit.admin")) return Boolean.TRUE;
    return null;
  }

  /** A player named in the file, or in the operators list, is one whose permissions are known. */
  @Override public boolean manages(PermissionSubject subject) {
    if (!(subject instanceof Player player)) return false;
    Loaded current = loaded;
    if (current.groups().isEmpty() && current.users().isEmpty()) return false;
    return current.users().containsKey(player.username().toLowerCase(Locale.ROOT))
        || current.users().containsKey(player.uniqueId().toString().toLowerCase(Locale.ROOT));
  }

  private static List<String> groupsOf(Loaded current, Player player) {
    List<String> groups = new ArrayList<>();
    groups.add(DEFAULT_GROUP);
    groups.addAll(current.users().getOrDefault(player.uniqueId().toString().toLowerCase(Locale.ROOT), List.of()));
    groups.addAll(current.users().getOrDefault(player.username().toLowerCase(Locale.ROOT), List.of()));
    return groups;
  }

  private static void collect(Loaded current, String name, Set<String> granted, Set<String> denied, Set<String> visited) {
    if (!visited.add(name)) return;
    Group group = current.groups().get(name);
    if (group == null) return;
    for (String parent : group.inherits()) collect(current, parent, granted, denied, visited);
    granted.addAll(group.granted());
    denied.addAll(group.denied());
  }

  /** The subset of TOML the file uses: {@code [section]} headers and {@code key = [..]} lists. */
  static Loaded parse(List<String> lines) {
    Map<String, Group> groups = new LinkedHashMap<>();
    Map<String, List<String>> users = new HashMap<>();
    Map<String, List<String>> inherits = new HashMap<>();
    Map<String, Set<String>> granted = new HashMap<>();
    Map<String, Set<String>> denied = new HashMap<>();
    String section = "";
    int number = 0;
    for (String raw : lines) {
      number++;
      String line = stripComment(raw).strip();
      if (line.isEmpty()) continue;
      if (line.startsWith("[")) {
        if (!line.endsWith("]")) throw new IllegalArgumentException("line " + number + ": a [section] header must end with ]");
        section = line.substring(1, line.length() - 1).strip();
        if (section.startsWith("groups.")) {
          String group = section.substring("groups.".length()).toLowerCase(Locale.ROOT);
          inherits.putIfAbsent(group, new ArrayList<>());
          granted.putIfAbsent(group, new HashSet<>());
          denied.putIfAbsent(group, new HashSet<>());
        } else if (!section.equals("users")) {
          throw new IllegalArgumentException("line " + number + ": [" + section + "] is not [groups.<name>] or [users]");
        }
        continue;
      }
      int equals = line.indexOf('=');
      if (equals < 0) throw new IllegalArgumentException("line " + number + ": expected key = [..]");
      String key = unquote(line.substring(0, equals).strip());
      List<String> list = list(line.substring(equals + 1).strip(), number);
      if (section.startsWith("groups.")) {
        String group = section.substring("groups.".length()).toLowerCase(Locale.ROOT);
        switch (key) {
          case "inherits" -> inherits.get(group).addAll(list.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList());
          case "permissions" -> {
            for (String node : list) {
              String lower = node.toLowerCase(Locale.ROOT);
              if (lower.startsWith("-")) denied.get(group).add(lower.substring(1)); else granted.get(group).add(lower);
            }
          }
          default -> throw new IllegalArgumentException("line " + number + ": a group takes inherits and permissions, not " + key);
        }
      } else if (section.equals("users")) {
        users.put(key.toLowerCase(Locale.ROOT), list.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList());
      } else {
        throw new IllegalArgumentException("line " + number + ": a setting must come after a [section] header");
      }
    }
    for (String group : inherits.keySet()) groups.put(group, new Group(inherits.get(group), granted.get(group), denied.get(group)));
    for (Group group : groups.values()) {
      for (String parent : group.inherits()) {
        if (!groups.containsKey(parent)) throw new IllegalArgumentException("group inherits from " + parent + ", which is not defined");
      }
    }
    return new Loaded(Map.copyOf(groups), Map.copyOf(users));
  }

  private static List<String> list(String written, int number) {
    if (!written.startsWith("[") || !written.endsWith("]")) throw new IllegalArgumentException("line " + number + ": expected a [list]");
    List<String> out = new ArrayList<>();
    for (String part : written.substring(1, written.length() - 1).split(",")) {
      String item = unquote(part.strip());
      if (!item.isEmpty()) out.add(item);
    }
    return out;
  }

  private static String unquote(String text) {
    if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) return text.substring(1, text.length() - 1);
    return text;
  }

  private static String stripComment(String line) {
    boolean quoted = false;
    for (int index = 0; index < line.length(); index++) {
      char c = line.charAt(index);
      if (c == '"') quoted = !quoted;
      else if (c == '#' && !quoted) return line.substring(0, index);
    }
    return line;
  }
}
