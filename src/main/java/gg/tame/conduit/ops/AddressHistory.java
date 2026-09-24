// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.ops;

import gg.tame.conduit.log.ConduitLog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Which accounts have logged in from which addresses, for {@code /galts}.
 *
 * <p>One record per account and address, with the name last seen under and when. Kept in
 * {@code addresses.txt} beside the configuration and rewritten on each new pairing; a login from an
 * address already on record for that account only refreshes the name and the time, which is not
 * written until the next new pairing. The oldest pairings go once the file holds {@link #LIMIT}, so
 * a busy network's history stays a file that can be read, not a database.
 */
public final class AddressHistory {
  private static final String FILE = "addresses.txt";
  /** ponytail: a flat cap on records; per-account pruning if a network outgrows it. */
  static final int LIMIT = 50_000;

  /** One account seen from one address. */
  public record Seen(UUID account, String username, String address, long lastSeen) {}

  private final Path file;
  /** Keyed by account + address, insertion-ordered so the oldest pairing is first. */
  private final Map<String, Seen> records = new LinkedHashMap<>();

  public AddressHistory(Path configDirectory) {
    this.file = configDirectory.resolve(FILE);
    load();
  }

  /** Records a login. Returns true when the pairing is new. */
  public synchronized boolean record(UUID account, String username, String address) {
    if (account == null || address == null || address.isBlank()) return false;
    String key = account + "\t" + address;
    boolean fresh = !records.containsKey(key);
    records.remove(key);
    records.put(key, new Seen(account, username, address, System.currentTimeMillis()));
    while (records.size() > LIMIT) records.remove(records.keySet().iterator().next());
    if (fresh) persist();
    return fresh;
  }

  /** Every address this name or account has been seen from. */
  public synchronized List<Seen> addressesOf(String username, UUID account) {
    List<Seen> out = new ArrayList<>();
    for (Seen seen : records.values()) {
      if (account != null && seen.account().equals(account)
          || username != null && seen.username().equalsIgnoreCase(username)) out.add(seen);
    }
    return out;
  }

  /**
   * Every other account seen from any address {@code username} has used, newest first. An account
   * is one entry however many addresses it shares.
   */
  public synchronized List<Seen> alts(String username) {
    Set<String> addresses = new TreeSet<>();
    Set<UUID> self = new java.util.HashSet<>();
    for (Seen seen : addressesOf(username, null)) { addresses.add(seen.address()); self.add(seen.account()); }
    // Which accounts share an address, then each one's newest record from anywhere: the name and
    // the time an alt was last seen are not tied to the address it shared.
    Set<UUID> matched = new java.util.HashSet<>();
    for (Seen seen : records.values()) {
      if (!self.contains(seen.account()) && addresses.contains(seen.address())) matched.add(seen.account());
    }
    Map<UUID, Seen> others = new LinkedHashMap<>();
    for (Seen seen : records.values()) {
      if (!matched.contains(seen.account())) continue;
      Seen known = others.get(seen.account());
      if (known == null || known.lastSeen() < seen.lastSeen()) others.put(seen.account(), seen);
    }
    List<Seen> out = new ArrayList<>(others.values());
    out.sort((left, right) -> Long.compare(right.lastSeen(), left.lastSeen()));
    return out;
  }

  private void load() {
    if (!Files.isRegularFile(file)) return;
    try {
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        if (line.isBlank() || line.charAt(0) == '#') continue;
        String[] parts = line.split("\t", -1);
        if (parts.length < 4) continue;
        try {
          UUID account = UUID.fromString(parts[0].toLowerCase(Locale.ROOT));
          records.put(account + "\t" + parts[2], new Seen(account, parts[1], parts[2], Long.parseLong(parts[3])));
        } catch (IllegalArgumentException malformed) {
          ConduitLog.warn("Ignoring a line in " + FILE + " that could not be read: " + line);
        }
      }
    } catch (IOException unreadable) {
      ConduitLog.warn("Could not read " + file + ", so /galts knows nothing from before this start: " + unreadable.getMessage());
    }
  }

  private void persist() {
    StringBuilder out = new StringBuilder();
    out.append("# Which accounts logged in from which addresses, for /galts. Written by Conduit.\n");
    for (Seen seen : records.values()) {
      out.append(seen.account()).append('\t').append(seen.username()).append('\t')
          .append(seen.address()).append('\t').append(seen.lastSeen()).append('\n');
    }
    try {
      AtomicFiles.write(file, out.toString());
    } catch (IOException unwritable) {
      ConduitLog.warn("Could not write " + file + ": " + unwritable.getMessage());
    }
  }
}
