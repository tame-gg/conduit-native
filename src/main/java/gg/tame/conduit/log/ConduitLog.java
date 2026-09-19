// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.log;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Structured levels. TRACE stays opt-in via -Dconduit.trace=true. */
public final class ConduitLog {
  public enum Level { ERROR, WARN, INFO, DEBUG, TRACE }
  /**
   * The time zone is the system's, resolved once: an operator reading the console reads it in the
   * zone the machine is set to, and the abbreviation is there so a log pulled off a box in another
   * zone can still be lined up against this one.
   */
  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("HH:mm:ss zzz").withZone(ZoneId.systemDefault());
  private static final boolean TRACE = Boolean.getBoolean("conduit.trace");
  private static final boolean DEBUG = TRACE || Boolean.getBoolean("conduit.debug");
  private ConduitLog() {}
  public static boolean trace() { return TRACE; }
  public static boolean debug() { return DEBUG; }
  public static void error(String message) { emit(Level.ERROR, message, null); }
  public static void error(String message, Throwable thrown) { emit(Level.ERROR, message, thrown); }
  public static void warn(String message) { emit(Level.WARN, message, null); }
  public static void info(String message) { emit(Level.INFO, message, null); }
  public static void debug(String message) { if (DEBUG) emit(Level.DEBUG, message, null); }
  public static void trace(String message) { if (TRACE) emit(Level.TRACE, message, null); }
  /**
   * The {@code [12:34:56 CET INFO]:} that opens every console line, Conduit's own and the ones
   * {@link ConduitConsoleLogging} relays from java.util.logging, so the two cannot drift apart.
   */
  static String prefix(Level level) {
    return "[" + STAMP.format(Instant.now()) + " " + level + "]:";
  }
  private static void emit(Level level, String message, Throwable thrown) {
    String line = ConduitColors.paint(level, prefix(level) + " " + message);
    if (level == Level.ERROR || level == Level.WARN) {
      System.err.println(line);
      if (thrown != null) thrown.printStackTrace(System.err);
    } else {
      System.out.println(line);
    }
  }
}
