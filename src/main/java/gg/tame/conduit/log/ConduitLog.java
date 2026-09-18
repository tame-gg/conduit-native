// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.log;

/** Structured levels. TRACE stays opt-in via -Dconduit.trace=true. */
public final class ConduitLog {
  public enum Level { ERROR, WARN, INFO, DEBUG, TRACE }
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
  private static void emit(Level level, String message, Throwable thrown) {
    String line = level + " " + message;
    if (level == Level.ERROR || level == Level.WARN) {
      System.err.println(line);
      if (thrown != null) thrown.printStackTrace(System.err);
    } else {
      System.out.println(line);
    }
  }
}
