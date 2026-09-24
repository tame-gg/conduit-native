// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.ops;

import gg.tame.conduit.log.ConduitLog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Who may not chat on this network, held like {@link BanList}: in memory, written through to
 * {@code mutes.txt} on every change, matched at every chat line. A mute is on the account, with the
 * name kept for the file and for {@code /gunmute} by name. Commands still go through, so a muted
 * player can still {@code /server} away and ask staff for help through the commands that exist for it.
 */
public final class Mutes {
  private static final String FILE = "mutes.txt";
  private static final char FIELD = '\t';

  public record Entry(UUID account, String username, String reason, String actor, long createdAt, long expiresAt) {
    public boolean permanent() { return expiresAt == BanList.PERMANENT; }
    public boolean expired(long now) { return !permanent() && now >= expiresAt; }
    public Optional<String> remaining(long now) {
      return permanent() ? Optional.empty() : Optional.of(BanList.describeDuration(Math.max(0, expiresAt - now)));
    }
  }

  private final Path file;
  private final CopyOnWriteArrayList<Entry> entries = new CopyOnWriteArrayList<>();

  public Mutes(Path configDirectory) {
    this.file = configDirectory.resolve(FILE);
    load();
  }

  /** The mute on this account or, for a login from before the account was known, this name. */
  public Optional<Entry> find(UUID account, String username) {
    long now = System.currentTimeMillis();
    for (Entry entry : entries) {
      if (entry.expired(now)) continue;
      if (account != null && entry.account().equals(account)) return Optional.of(entry);
      if (username != null && entry.username().equalsIgnoreCase(username)) return Optional.of(entry);
    }
    return Optional.empty();
  }

  public synchronized Entry mute(UUID account, String username, String reason, String actor, long expiresAt) {
    entries.removeIf(entry -> entry.account().equals(account));
    Entry entry = new Entry(account, username, reason == null || reason.isBlank() ? "Muted." : reason,
        actor == null || actor.isBlank() ? "console" : actor, System.currentTimeMillis(), expiresAt);
    entries.add(entry);
    persist();
    return entry;
  }

  /** Lifts a mute by account or name. False when none was in force. */
  public synchronized boolean unmute(String usernameOrAccount) {
    long now = System.currentTimeMillis();
    String key = usernameOrAccount.strip().toLowerCase(Locale.ROOT);
    boolean removed = entries.removeIf(entry -> !entry.expired(now)
        && (entry.username().toLowerCase(Locale.ROOT).equals(key) || entry.account().toString().equals(key)));
    entries.removeIf(entry -> entry.username().toLowerCase(Locale.ROOT).equals(key) || entry.account().toString().equals(key));
    if (removed) persist();
    return removed;
  }

  private void load() {
    if (!Files.isRegularFile(file)) return;
    try {
      long now = System.currentTimeMillis();
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        if (line.isBlank() || line.charAt(0) == '#') continue;
        String[] parts = line.split("\t", -1);
        if (parts.length < 6) continue;
        try {
          Entry entry = new Entry(UUID.fromString(parts[0]), parts[1], unescape(parts[2]), unescape(parts[3]),
              Long.parseLong(parts[4]), Long.parseLong(parts[5]));
          if (!entry.expired(now)) entries.add(entry);
        } catch (IllegalArgumentException malformed) {
          ConduitLog.warn("Ignoring a line in " + FILE + " that could not be read: " + line);
        }
      }
    } catch (IOException unreadable) {
      ConduitLog.warn("Could not read " + file + ", so nobody is muted this start: " + unreadable.getMessage());
    }
  }

  private void persist() {
    StringBuilder out = new StringBuilder("# Conduit's mutes: account, name, reason, by, made, expires (ms; ")
        .append(BanList.PERMANENT).append(" is never). Written by /gmute; edit only while the proxy is stopped.\n");
    for (Entry entry : entries) {
      out.append(entry.account()).append(FIELD).append(entry.username()).append(FIELD).append(escape(entry.reason()))
          .append(FIELD).append(escape(entry.actor())).append(FIELD).append(entry.createdAt()).append(FIELD).append(entry.expiresAt()).append('\n');
    }
    try { AtomicFiles.write(file, out.toString()); }
    catch (IOException unwritable) { ConduitLog.warn("Could not write " + file + ", so mutes made now are lost on restart: " + unwritable.getMessage()); }
  }

  private static String escape(String text) { return text.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n"); }
  private static String unescape(String text) {
    StringBuilder out = new StringBuilder(text.length());
    for (int index = 0; index < text.length(); index++) {
      char c = text.charAt(index);
      if (c == '\\' && index + 1 < text.length()) {
        char next = text.charAt(++index);
        out.append(next == 't' ? '\t' : next == 'n' ? '\n' : next);
      } else out.append(c);
    }
    return out.toString();
  }
}
