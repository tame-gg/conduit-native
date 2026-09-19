// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.boot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The handful of settings the bootstrap needs, read before the real loader exists.
 *
 * <p>{@code ConfigurationLoader} is a Conduit class, and the bootstrap's whole job is to decide which
 * loader Conduit's classes go in -- so it cannot use one of them to decide. This reads the same keys
 * out of the same file with a scanner small enough to be obviously correct, and gets them wrong in the
 * safe direction: anything unreadable, absent or malformed leaves the defaults in place, and the real
 * loader will report the mistake properly a moment later.
 *
 * <p>Mirrors {@code gg.tame.conduit.config.UpdateSettings}; {@code BootConfigTest} checks the two agree
 * on the same file.
 */
public record BootConfig(boolean viaUpdates, boolean checkOnly, int timeoutMs) {
  /** Matches UpdateSettings.DEFAULT_VIA. */
  private static final boolean DEFAULT_VIA = true;
  /** Matches UpdateSettings.DEFAULT_TIMEOUT_MS. */
  private static final int DEFAULT_TIMEOUT_MS = 5000;

  public static BootConfig defaults() {
    return new BootConfig(DEFAULT_VIA, false, DEFAULT_TIMEOUT_MS);
  }

  /**
   * Reads {@code [updates]} from a configuration file.
   *
   * <p>A file that does not exist yet is the first-start case: the defaults are what it is about to be
   * written with, so they are the right answer.
   */
  public static BootConfig read(Path file) throws IOException {
    // Every path out of here goes through withOverride, including the two where there is nothing to
    // read. -Dconduit.via.update=false was silently ignored on a first start, because a file that does
    // not exist yet returned the defaults directly -- and a first start on a machine with no network
    // is exactly when someone reaches for that switch.
    List<String> lines;
    if (!Files.isRegularFile(file)) return withOverride(defaults());
    try {
      lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (IOException unreadable) {
      return withOverride(defaults());
    }
    boolean via = DEFAULT_VIA;
    boolean checkOnly = false;
    int timeoutMs = DEFAULT_TIMEOUT_MS;
    String section = "";
    for (String raw : lines) {
      String line = withoutComment(raw).strip();
      if (line.isEmpty()) continue;
      if (line.startsWith("[") && line.endsWith("]")) {
        section = line.substring(1, line.length() - 1);
        continue;
      }
      if (!section.equals("updates")) continue;
      int equals = line.indexOf('=');
      if (equals < 1) continue;
      String key = line.substring(0, equals).strip();
      String value = line.substring(equals + 1).strip();
      switch (key) {
        case "via" -> via = Boolean.parseBoolean(value);
        case "check-only" -> checkOnly = Boolean.parseBoolean(value);
        case "timeout-ms" -> {
          try {
            int parsed = Integer.parseInt(value);
            // The same bounds UpdateSettings enforces. Out of range keeps the default here rather than
            // refusing to start: the real loader is what reports it, with the line number.
            if (parsed >= 500 && parsed <= 60_000) timeoutMs = parsed;
          } catch (NumberFormatException notANumber) {
            // Left at the default; reported properly by the real loader.
          }
        }
        default -> { /* Not the bootstrap's to know about. */ }
      }
    }
    return withOverride(new BootConfig(via, checkOnly, timeoutMs));
  }

  /**
   * Applies {@code -Dconduit.via.update}, which beats the file.
   *
   * <p>What a one-off start needs when the file says otherwise: debugging an update, or bringing a
   * proxy up on a machine with no network without editing its configuration first.
   */
  private static BootConfig withOverride(BootConfig config) {
    String override = System.getProperty("conduit.via.update");
    if (override == null || override.isBlank()) return config;
    return new BootConfig(Boolean.parseBoolean(override), config.checkOnly(), config.timeoutMs());
  }

  /** The line up to a {@code #} outside a quoted string, as the real loader does it. */
  private static String withoutComment(String line) {
    boolean quoted = false;
    for (int index = 0; index < line.length(); index++) {
      char current = line.charAt(index);
      if (current == '"') quoted = !quoted;
      else if (current == '\\' && quoted) index++;
      else if (current == '#' && !quoted) return line.substring(0, index);
    }
    return line;
  }
}
