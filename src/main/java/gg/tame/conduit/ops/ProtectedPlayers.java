// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.ops;

import gg.tame.conduit.log.ConduitLog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who was out of staff's reach the last time they were seen: the players who held
 * {@code conduit.gkick}, {@code conduit.gban} or {@code conduit.punish.exempt} when they logged in
 * or left. A permissions plugin answers for the players it has loaded, which is the ones online, so
 * {@code /gban} on someone offline could not ask it and banned a fellow staff member the moment they
 * logged out. This is what it asks instead, written through to {@code protected-players.txt} beside
 * the configuration so it survives a restart.
 *
 * <p>A player is recorded as held or dropped every time they are seen, so someone demoted stops
 * being protected once they have logged in, or left, since.
 */
public final class ProtectedPlayers {
  private static final String FILE = "protected-players.txt";

  private final Path file;
  /** Account to the name it last carried; read on a ban, written on a login or a leave. */
  private final ConcurrentHashMap<UUID, String> names = new ConcurrentHashMap<>();

  public ProtectedPlayers(Path configDirectory) {
    this.file = configDirectory.resolve(FILE);
    load();
  }

  /** Whether this name, in any letter case, or this account was protected when last seen. */
  public boolean contains(String username, UUID account) {
    if (account != null && names.containsKey(account)) return true;
    if (username == null) return false;
    for (String name : names.values()) if (name.equalsIgnoreCase(username)) return true;
    return false;
  }

  /** Records that this player holds protection, or no longer does; nothing is written when nothing changed. */
  public synchronized void remember(UUID account, String username, boolean held) {
    boolean changed = held ? !username.equals(names.put(account, username)) : names.remove(account) != null;
    if (changed) persist();
  }

  private void load() {
    if (!Files.isRegularFile(file)) return;
    try {
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        String text = line.strip();
        if (text.isEmpty() || text.charAt(0) == '#') continue;
        String[] parts = text.split("\t", -1);
        if (parts.length != 2) continue;
        try { names.put(UUID.fromString(parts[0].toLowerCase(Locale.ROOT)), parts[1]); }
        catch (IllegalArgumentException malformed) { ConduitLog.warn("Ignoring a line in " + FILE + " that could not be read: " + line); }
      }
    } catch (IOException unreadable) {
      ConduitLog.warn("Could not read " + file + ", so no offline player is protected from /gban this start: " + unreadable.getMessage());
    }
  }

  private void persist() {
    StringBuilder out = new StringBuilder();
    out.append("# Conduit's record of who held conduit.gkick, conduit.gban or conduit.punish.exempt when last seen,\n");
    out.append("# so /gban cannot reach them while they are offline. Written at each login and leave; edit only while the proxy is stopped.\n");
    for (Map.Entry<UUID, String> entry : names.entrySet()) out.append(entry.getKey()).append('\t').append(entry.getValue()).append('\n');
    try {
      Path temporary = file.resolveSibling(FILE + ".tmp");
      Files.writeString(temporary, out.toString(), StandardCharsets.UTF_8);
      try {
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (java.nio.file.AtomicMoveNotSupportedException notAtomic) {
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException unwritable) {
      ConduitLog.warn("Could not write " + file + ", so who is protected from /gban is forgotten on restart: " + unwritable.getMessage());
    }
  }
}
