// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.ops;

import gg.tame.conduit.log.ConduitLog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Who may join while the network is closed.
 *
 * <p>Whether it is on, and who is on it, are both held here and written through to
 * {@code whitelist.txt} beside the configuration on every change. Neither is read from
 * {@code conduit.toml}: turning the whitelist on, or letting one more person in, is something an
 * operator does with players waiting, and waiting on a config reload to do it is the thing this
 * exists to avoid. Both survive a restart.
 *
 * <p>Names are matched case-insensitively and stored as typed, so {@code /gwhitelist list} shows an
 * operator what they wrote rather than a lowercased version of it.
 *
 * <p>This is deliberately not the maintenance allowlist. Maintenance is a temporary state with a
 * message on the server list, set from {@code conduit.toml}; the whitelist is a standing policy that
 * an operator edits in place. A network can have both on, and a player then has to pass both.
 */
public final class Whitelist {
  private static final String FILE = "whitelist.txt";

  private final Path file;
  private final AtomicBoolean enabled = new AtomicBoolean();
  /** Read on every login and written a command at a time, so the reads must never wait. */
  private final CopyOnWriteArraySet<String> names = new CopyOnWriteArraySet<>();

  public Whitelist(Path configDirectory) {
    this.file = configDirectory.resolve(FILE);
    load();
  }

  public boolean isEnabled() { return enabled.get(); }

  /** Every name on the list, as it was typed, in alphabetical order. */
  public List<String> names() {
    List<String> sorted = new ArrayList<>(names);
    sorted.sort(String.CASE_INSENSITIVE_ORDER);
    return sorted;
  }

  public int size() { return names.size(); }

  /**
   * Whether this player may join.
   *
   * <p>True whenever the whitelist is off, so nothing has to ask twice. A player holding
   * {@link gg.tame.conduit.command.Permissions#WHITELIST_BYPASS} is let in without being on it,
   * which is how the staff who turned it on do not lock themselves out.
   */
  public boolean allows(String username, boolean hasBypassPermission) {
    return allows(username, () -> hasBypassPermission);
  }

  /**
   * The same question with the bypass left unasked until it is needed.
   *
   * <p>Asking it costs a call into whatever permission plugin is installed, and the answer only ever
   * matters for a player the list itself turns away. A login that the whitelist has no opinion about
   * -- because it is off, or because the player is on it -- must not reach a permission plugin on
   * this account at all: a proxy whose whitelist is off would otherwise be asking a database about
   * every player who joins, and the maintenance allowlist's promise that it works with the
   * permission plugin broken or gone is decided on the same seam.
   */
  public boolean allows(String username, java.util.function.BooleanSupplier hasBypassPermission) {
    if (!enabled.get()) return true;
    if (contains(username)) return true;
    return hasBypassPermission.getAsBoolean();
  }

  public boolean contains(String username) {
    if (username == null) return false;
    String wanted = username.strip().toLowerCase(Locale.ROOT);
    for (String name : names) if (name.toLowerCase(Locale.ROOT).equals(wanted)) return true;
    return false;
  }

  /** Turns it on or off. False when it was already that way, which the command reports rather than lying. */
  public synchronized boolean setEnabled(boolean wanted) {
    if (enabled.get() == wanted) return false;
    enabled.set(wanted);
    persist();
    return true;
  }

  /** False when the name was already on the list. */
  public synchronized boolean add(String username) {
    if (username == null || username.isBlank()) return false;
    if (contains(username)) return false;
    names.add(username.strip());
    persist();
    return true;
  }

  /** False when the name was not on the list. */
  public synchronized boolean remove(String username) {
    if (username == null || username.isBlank()) return false;
    String wanted = username.strip().toLowerCase(Locale.ROOT);
    boolean removed = names.removeIf(name -> name.toLowerCase(Locale.ROOT).equals(wanted));
    if (removed) persist();
    return removed;
  }

  /** Everyone, at once. Returns how many were taken off. */
  public synchronized int clear() {
    int had = names.size();
    if (had == 0) return 0;
    names.clear();
    persist();
    return had;
  }

  private void load() {
    if (!Files.isRegularFile(file)) return;
    try {
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        String text = line.strip();
        if (text.isEmpty() || text.charAt(0) == '#') continue;
        if (text.startsWith("!")) { enabled.set(Boolean.parseBoolean(text.substring(1).strip())); continue; }
        names.add(text);
      }
    } catch (IOException unreadable) {
      // Falling back to "off" rather than "on with nobody on it": a whitelist that cannot be read
      // must not be the reason an entire network is shut out.
      ConduitLog.warn("Could not read " + file + ", so the whitelist is off this start: " + unreadable.getMessage());
    }
  }

  private void persist() {
    StringBuilder out = new StringBuilder();
    out.append("# Conduit whitelist. Written by /gwhitelist; edit only while the proxy is stopped.\n");
    out.append("# The !line is whether it is on; every other line is one player.\n");
    out.append('!').append(enabled.get()).append('\n');
    Set<String> written = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    written.addAll(names);
    for (String name : written) out.append(name).append('\n');
    try {
      Path temporary = file.resolveSibling(FILE + ".tmp");
      Files.writeString(temporary, out.toString(), StandardCharsets.UTF_8);
      try {
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (java.nio.file.AtomicMoveNotSupportedException notAtomic) {
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException unwritable) {
      ConduitLog.warn("Could not write " + file + ", so whitelist changes made now are lost on restart: "
          + unwritable.getMessage());
    }
  }
}
