// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.ops;

import gg.tame.conduit.log.ConduitLog;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Who may not join this network, by name, by account or by address.
 *
 * <p>Held in memory and written through to {@code bans.txt} beside the configuration on every
 * change, so a ban is in force the instant the command returns and survives a restart. Nothing here
 * is read from {@code conduit.toml} and nothing needs a reload: a ban is an operational decision
 * taken while the proxy is running, not a setting.
 *
 * <p>A name ban is matched case-insensitively, because that is how an operator types it and how
 * Minecraft treats names. An account ban is matched on the UUID, and so survives a name change. An
 * address ban is matched on the address the connection came from, or on a range written as CIDR
 * ({@code 203.0.113.0/24}, {@code 2001:db8::/32}) for a host that keeps moving within one.
 *
 * <p>A ban may end. {@code expiresAt} of {@link #PERMANENT} never does; anything else is an instant
 * in epoch milliseconds, and an entry past it stops matching and is dropped the next time the list
 * is written. Nothing prunes on a timer: an expired ban that no one looks at costs a line in a file.
 */
public final class BanList {
  /** An {@code expiresAt} that never arrives. */
  public static final long PERMANENT = Long.MAX_VALUE;
  private static final String FILE = "bans.txt";
  /** Every ban, unban and refused login, one line each, appended and never rewritten. */
  private static final String AUDIT = "bans.log";
  /** Separates the fields of one record. Neither a name, a UUID nor an address may contain it. */
  private static final char FIELD = '\t';

  /** What a ban is on. */
  public enum Kind { NAME, ACCOUNT, ADDRESS }

  /**
   * One ban. {@code value} is the name, the UUID or the address, already in the form
   * {@link #normalise} puts it in, so a lookup is an equality test.
   */
  public record Entry(Kind kind, String value, String reason, String actor, long createdAt, long expiresAt, String alias) {
    public Entry {
      if (kind == null) throw new IllegalArgumentException("a ban needs a kind");
      if (value == null || value.isBlank()) throw new IllegalArgumentException("a ban needs something to ban");
      if (reason == null || reason.isBlank()) reason = "Banned from this network.";
      if (actor == null || actor.isBlank()) actor = "console";
      alias = alias == null ? "" : normalise(Kind.NAME, alias);
    }

    /** A ban that was not made alongside a name ban. */
    public Entry(Kind kind, String value, String reason, String actor, long createdAt, long expiresAt) {
      this(kind, value, reason, actor, createdAt, expiresAt, "");
    }

    public boolean permanent() { return expiresAt == PERMANENT; }
    public boolean expired(long now) { return !permanent() && now >= expiresAt; }

    /** How long is left, for a kick screen: empty when the ban is permanent. */
    public Optional<String> remaining(long now) {
      if (permanent()) return Optional.empty();
      return Optional.of(describeDuration(Math.max(0, expiresAt - now)));
    }
  }

  private final Path file;
  private final Path audit;
  /**
   * Copy-on-write because every login reads this and only an operator writes it: a read must never
   * wait behind a write, and the writes are a command at a time.
   */
  private final CopyOnWriteArrayList<Entry> entries = new CopyOnWriteArrayList<>();

  public BanList(Path configDirectory) {
    this.file = configDirectory.resolve(FILE);
    this.audit = configDirectory.resolve(AUDIT);
    load();
  }

  /** The name, account or address a ban is recorded and matched under. */
  public static String normalise(Kind kind, String value) {
    String trimmed = value == null ? "" : value.strip();
    if (kind == Kind.NAME) return trimmed.toLowerCase(Locale.ROOT);
    if (kind != Kind.ADDRESS) return trimmed;
    // An address is matched by what the connection reports, which for IPv6 is the expanded form:
    // "::1" typed by an operator and "0:0:0:0:0:0:0:1" seen at login are the same address. A scope
    // ("%eth0") is dropped on both sides: it names an interface, not a client.
    int scope = trimmed.indexOf('%');
    if (scope >= 0) trimmed = trimmed.substring(0, scope);
    // A range is kept as its network address and prefix -- "10.0.0.7/24" is the same ban as
    // "10.0.0.0/24" -- so that two operators typing it differently get one entry, not two.
    int slash = trimmed.indexOf('/');
    if (slash >= 0) {
      Optional<InetAddress> network = literalAddress(trimmed.substring(0, slash));
      Integer prefix = prefixLength(trimmed.substring(slash + 1), network);
      if (network.isEmpty() || prefix == null) return trimmed;
      byte[] bytes = network.get().getAddress();
      mask(bytes, prefix);
      try {
        return InetAddress.getByAddress(bytes).getHostAddress() + "/" + prefix;
      } catch (java.net.UnknownHostException impossible) {
        return trimmed;
      }
    }
    return literalAddress(trimmed).map(InetAddress::getHostAddress).orElse(trimmed);
  }

  /** Whether {@code text} is an address or a CIDR range this list can match, for a command to check first. */
  public static boolean validAddress(String text) {
    String trimmed = text == null ? "" : text.strip();
    int scope = trimmed.indexOf('%');
    if (scope >= 0) trimmed = trimmed.substring(0, scope);
    int slash = trimmed.indexOf('/');
    if (slash < 0) return literalAddress(trimmed).isPresent();
    Optional<InetAddress> network = literalAddress(trimmed.substring(0, slash));
    return network.isPresent() && prefixLength(trimmed.substring(slash + 1), network) != null;
  }

  /**
   * Whether a recorded address ban, in its normalised form, covers a connection's address.
   *
   * <p>A plain entry is an equality test. A range entry compares the leading {@code prefix} bits, and
   * only within one address family: a /24 on IPv4 says nothing about any IPv6 address.
   */
  public static boolean covers(String banned, String address) {
    int slash = banned.indexOf('/');
    // A range asked about a range is "is this the same ban", which is what /gban asks before adding one.
    if (slash < 0 || address.indexOf('/') >= 0) return banned.equals(normalise(Kind.ADDRESS, address));
    Optional<InetAddress> network = literalAddress(banned.substring(0, slash));
    Optional<InetAddress> candidate = literalAddress(normalise(Kind.ADDRESS, address));
    Integer prefix = prefixLength(banned.substring(slash + 1), network);
    if (network.isEmpty() || candidate.isEmpty() || prefix == null) return false;
    byte[] left = network.get().getAddress();
    byte[] right = candidate.get().getAddress();
    if (left.length != right.length) return false;
    mask(right, prefix);
    return java.util.Arrays.equals(left, right);
  }

  /** The prefix length after the slash, or null when it is not a number the address family allows. */
  private static Integer prefixLength(String text, Optional<InetAddress> network) {
    if (network.isEmpty() || text.isEmpty() || text.length() > 3) return null;
    for (int index = 0; index < text.length(); index++) {
      if (text.charAt(index) < '0' || text.charAt(index) > '9') return null;
    }
    int prefix = Integer.parseInt(text);
    return prefix <= network.get().getAddress().length * 8 ? prefix : null;
  }

  /** Clears every bit after the first {@code prefix}, in place. */
  private static void mask(byte[] bytes, int prefix) {
    for (int index = 0; index < bytes.length; index++) {
      int bits = Math.max(0, Math.min(8, prefix - index * 8));
      bytes[index] &= (byte) (0xFF << (8 - bits));
    }
  }

  /**
   * A literal address, or nothing. Passing an operator-supplied string to
   * {@link InetAddress#getByName} would turn a command into a name lookup, so IPv4 is parsed here and
   * built with {@code getByAddress}, which never resolves; anything containing a colon is an IPv6
   * literal, which getByName validates without resolving either.
   */
  public static Optional<InetAddress> literalAddress(String text) {
    try {
      if (text.indexOf(':') >= 0) return Optional.of(InetAddress.getByName(text));
      String[] parts = text.split("[.]", -1);
      if (parts.length != 4) return Optional.empty();
      byte[] octets = new byte[4];
      for (int index = 0; index < 4; index++) {
        String part = parts[index];
        if (part.isEmpty() || part.length() > 3) return Optional.empty();
        for (int digit = 0; digit < part.length(); digit++) {
          if (part.charAt(digit) < '0' || part.charAt(digit) > '9') return Optional.empty();
        }
        int octet = Integer.parseInt(part);
        if (octet > 255) return Optional.empty();
        octets[index] = (byte) octet;
      }
      return Optional.of(InetAddress.getByAddress(octets));
    } catch (java.net.UnknownHostException notALiteral) {
      return Optional.empty();
    }
  }

  /**
   * The ban that turns this login away, if any.
   *
   * <p>All three are asked, so a player who changed their name is still caught by their account and
   * a banned address is caught whatever name it arrives under. Any argument may be null: a login
   * that has not got that far yet simply cannot match on it.
   */
  public Optional<Entry> find(String username, UUID account, String address) {
    long now = System.currentTimeMillis();
    for (Entry entry : entries) {
      if (entry.expired(now)) continue;
      boolean hit = switch (entry.kind()) {
        case NAME -> username != null && entry.value().equals(normalise(Kind.NAME, username));
        case ACCOUNT -> account != null && entry.value().equals(account.toString());
        case ADDRESS -> address != null && covers(entry.value(), address);
      };
      if (hit) return Optional.of(entry);
    }
    return Optional.empty();
  }

  /** Every ban still in force, newest first, for {@code /gban list}. */
  public List<Entry> active() {
    long now = System.currentTimeMillis();
    List<Entry> live = new ArrayList<>();
    for (Entry entry : entries) if (!entry.expired(now)) live.add(entry);
    live.sort((left, right) -> Long.compare(right.createdAt(), left.createdAt()));
    return live;
  }

  /**
   * Adds a ban, replacing any the same thing already had, and writes the file.
   *
   * <p>Replacing rather than stacking is what an operator means by banning someone who is already
   * banned: the new reason and the new length are the ones that count, and a list with one entry per
   * attempt is a list nobody can read.
   */
  public synchronized Entry ban(Kind kind, String value, String reason, String actor, long expiresAt) {
    return ban(kind, value, reason, actor, expiresAt, "");
  }

  /**
   * As above, for an account ban made alongside a name ban: {@code alias} is that name, so lifting the
   * name's ban lifts this one too. An account ban left behind by an unban kept the player out.
   */
  public synchronized Entry ban(Kind kind, String value, String reason, String actor, long expiresAt, String alias) {
    String normalised = normalise(kind, value);
    entries.removeIf(entry -> entry.kind() == kind && entry.value().equals(normalised));
    Entry entry = new Entry(kind, normalised, reason, actor, System.currentTimeMillis(), expiresAt, alias);
    entries.add(entry);
    persist();
    audit("BAN " + kind + " " + normalised + " by " + entry.actor() + " until "
        + (entry.permanent() ? "forever" : java.time.Instant.ofEpochMilli(expiresAt)) + ": " + entry.reason());
    return entry;
  }

  /** Lifts a ban. False when there was none in force, which is worth telling the operator. */
  public synchronized boolean pardon(Kind kind, String value) {
    String normalised = normalise(kind, value);
    long now = System.currentTimeMillis();
    if (kind == Kind.NAME) {
      // The account banned alongside this name goes with it. One made before bans recorded their
      // name is recognised as the one the same /gban wrote: same reason, same staff member, same
      // expiry, written within a second of the name's.
      List<Entry> named = new ArrayList<>();
      for (Entry entry : entries) if (entry.kind() == Kind.NAME && entry.value().equals(normalised)) named.add(entry);
      entries.removeIf(entry -> entry.kind() == Kind.ACCOUNT && (entry.alias().equals(normalised)
          || entry.alias().isEmpty() && named.stream().anyMatch(name -> sameCommand(name, entry))));
    }
    boolean removed = entries.removeIf(entry ->
        entry.kind() == kind && entry.value().equals(normalised) && !entry.expired(now));
    // Expired entries for the same thing go with it, so a pardon leaves nothing behind.
    entries.removeIf(entry -> entry.kind() == kind && entry.value().equals(normalised));
    if (removed) { persist(); audit("UNBAN " + kind + " " + normalised); }
    return removed;
  }

  private static boolean sameCommand(Entry name, Entry account) {
    return name.reason().equals(account.reason()) && name.actor().equals(account.actor())
        && name.expiresAt() == account.expiresAt() && Math.abs(name.createdAt() - account.createdAt()) <= 1000;
  }

  /** Lifts whatever ban of any kind is held against this text, for a {@code /gunban} that is given one word. */
  /** Records that a login was turned away by {@code entry}, for the audit log. */
  public void refused(String username, String address, Entry entry) {
    audit("REFUSED " + username + " from " + address + " by " + entry.kind() + " " + entry.value() + ": " + entry.reason());
  }

  /**
   * One line to {@code bans.log}. Appended, never rewritten: the file is the history a dispute a week
   * later is settled from, and it survives every edit to bans.txt. A failure to write it is one
   * warning, since the ban itself is already in force.
   */
  private synchronized void audit(String line) {
    try {
      Files.writeString(audit, java.time.Instant.now().toString() + " " + line + System.lineSeparator(),
          StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    } catch (IOException unwritable) {
      ConduitLog.warn("Could not append to " + audit + ": " + unwritable.getMessage());
    }
  }

  public synchronized boolean pardonAny(String value) {
    boolean removed = false;
    for (Kind kind : Kind.values()) if (pardon(kind, value)) removed = true;
    return removed;
  }

  /**
   * Reads a duration such as {@code 30m}, {@code 2h}, {@code 7d} or {@code perm} into an expiry.
   *
   * <p>Empty means the word was not a duration at all, which is how {@code /gban Steve rude} tells a
   * reason from a length without the operator having to say which is which.
   */
  public static Optional<Long> parseExpiry(String written) {
    String text = written == null ? "" : written.strip().toLowerCase(Locale.ROOT);
    if (text.isEmpty()) return Optional.empty();
    if (text.equals("perm") || text.equals("permanent") || text.equals("forever")) return Optional.of(PERMANENT);
    char unit = text.charAt(text.length() - 1);
    String number = text.substring(0, text.length() - 1);
    if (number.isEmpty()) return Optional.empty();
    long multiplier = switch (unit) {
      case 's' -> 1000L;
      case 'm' -> 60_000L;
      case 'h' -> 3_600_000L;
      case 'd' -> 86_400_000L;
      case 'w' -> 604_800_000L;
      default -> 0L;
    };
    if (multiplier == 0) return Optional.empty();
    try {
      long amount = Long.parseLong(number);
      if (amount <= 0) return Optional.empty();
      // A length that would overflow past the end of time is simply a permanent ban.
      if (amount > (Long.MAX_VALUE - System.currentTimeMillis()) / multiplier) return Optional.of(PERMANENT);
      return Optional.of(System.currentTimeMillis() + amount * multiplier);
    } catch (NumberFormatException notANumber) {
      return Optional.empty();
    }
  }

  /** {@code 2d 3h} and so on, for a kick screen and for {@code /gban list}. */
  public static String describeDuration(long millis) {
    long seconds = Math.max(0, millis / 1000);
    long days = seconds / 86_400;
    long hours = (seconds % 86_400) / 3600;
    long minutes = (seconds % 3600) / 60;
    if (days > 0) return hours > 0 ? days + "d " + hours + "h" : days + "d";
    if (hours > 0) return minutes > 0 ? hours + "h " + minutes + "m" : hours + "h";
    if (minutes > 0) return minutes + "m";
    return Math.max(1, seconds) + "s";
  }

  /** Whether this looks like an address rather than a player name, so {@code /gban} can tell them apart. */
  public static boolean looksLikeAddress(String text) {
    if (text == null || text.isBlank()) return false;
    // A Minecraft name is letters, digits and underscore, so anything with a dot or a colon in it is
    // an address. That is the whole test: a hostname is not something to ban, and a malformed
    // address simply never matches a connection.
    return text.indexOf('.') >= 0 || text.indexOf(':') >= 0;
  }

  private void load() {
    if (!Files.isRegularFile(file)) return;
    try {
      long now = System.currentTimeMillis();
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        if (line.isBlank() || line.charAt(0) == '#') continue;
        Entry entry = parse(line);
        // An expired ban is not carried into memory; the next write drops it from the file.
        if (entry != null && !entry.expired(now)) entries.add(entry);
      }
    } catch (IOException unreadable) {
      ConduitLog.warn("Could not read " + file + ", so nobody is banned this start: " + unreadable.getMessage());
    }
  }

  private static Entry parse(String line) {
    String[] parts = line.split("\t", -1);
    if (parts.length < 6) return null;
    try {
      // The seventh field, the name an account ban was made alongside, is newer than the file format.
      // Normalised on the way in too, so an address written by an older build still matches.
      Kind kind = Kind.valueOf(parts[0]);
      return new Entry(kind, normalise(kind, parts[1]), unescape(parts[2]), unescape(parts[3]),
          Long.parseLong(parts[4]), Long.parseLong(parts[5]), parts.length > 6 ? unescape(parts[6]) : "");
    } catch (IllegalArgumentException malformed) {
      // One unreadable line is one ban lost, not a proxy that will not start.
      ConduitLog.warn("Ignoring a line in bans.txt that could not be read: " + line);
      return null;
    }
  }

  /**
   * Writes the whole list, through a temporary file, so a crash mid-write cannot leave a truncated
   * list that would silently unban people.
   */
  private void persist() {
    long now = System.currentTimeMillis();
    entries.removeIf(entry -> entry.expired(now));
    StringBuilder out = new StringBuilder();
    out.append("# Conduit bans. Written by /gban; edit only while the proxy is stopped.\n");
    out.append("# kind\tvalue\treason\tactor\tcreated\texpires (").append(PERMANENT).append(" = permanent)\t[name an account ban was made with]\n");
    for (Entry entry : entries) {
      out.append(entry.kind().name()).append(FIELD).append(entry.value()).append(FIELD)
          .append(escape(entry.reason())).append(FIELD).append(escape(entry.actor())).append(FIELD)
          .append(entry.createdAt()).append(FIELD).append(entry.expiresAt());
      if (!entry.alias().isEmpty()) out.append(FIELD).append(escape(entry.alias()));
      out.append('\n');
    }
    try {
      AtomicFiles.write(file, out.toString());
    } catch (IOException unwritable) {
      // The ban is in force either way; it is the surviving of a restart that was lost.
      ConduitLog.warn("Could not write " + file + ", so bans made now are lost on restart: "
          + unwritable.getMessage());
    }
  }

  /** A reason is free text an operator typed, so the two characters the format owns are taken out of it. */
  private static String escape(String text) { return text.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n"); }

  private static String unescape(String text) {
    StringBuilder out = new StringBuilder(text.length());
    for (int index = 0; index < text.length(); index++) {
      char current = text.charAt(index);
      if (current != '\\' || index + 1 == text.length()) { out.append(current); continue; }
      char next = text.charAt(++index);
      out.append(switch (next) { case 't' -> '\t'; case 'n' -> '\n'; default -> next; });
    }
    return out.toString();
  }
}
