// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.log;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Puts everything that logs through {@code java.util.logging} -- ViaVersion, ViaBackwards, the
 * Velocity compatibility layer and any plugin using SLF4J over JUL -- onto the same one-line,
 * colour-coded console as {@link ConduitLog}.
 *
 * <p>Without this the same console carried two formats: Conduit's own stamped line and JUL's
 * default two lines, a timestamp and class name followed by {@code SEVERE: message}. Worse, JUL's
 * {@code ConsoleHandler} sends every level to stderr, so ordinary Via progress lines arrived on the
 * error stream and a shell that separates the two showed them as failures.
 *
 * <p>Installed once, from the launcher, before anything else can log.
 */
public final class ConduitConsoleLogging {
  private static boolean installed;

  private ConduitConsoleLogging() {}

  /** Replaces the root handlers with one Conduit-formatted console handler. */
  public static synchronized void install() {
    if (installed) return;
    installed = true;
    Logger root = LogManager.getLogManager().getLogger("");
    if (root == null) return;
    for (Handler existing : root.getHandlers()) root.removeHandler(existing);
    root.addHandler(new ConduitHandler());
    // FINE and below are Conduit's own debug/trace gate, not JUL's: without this a
    // -Dconduit.debug run still lost every library's debug line to the root's INFO level.
    root.setLevel(ConduitLog.trace() ? Level.ALL : ConduitLog.debug() ? Level.FINE : Level.INFO);
  }

  private static final class ConduitHandler extends Handler {
    @Override public void publish(LogRecord record) {
      if (record == null || !isLoggable(record)) return;
      ConduitLog.Level level = translate(record.getLevel());
      String source = shortName(record.getLoggerName());
      String message = String.valueOf(record.getMessage());
      // getMessage(), not a formatted one: Via passes whole sentences, and the
      // parameterised form would need a ResourceBundle none of these loggers set.
      String line = ConduitColors.paint(level, ConduitLog.prefix(level) + (source.isEmpty() ? " " : " [" + source + "] ") + message);
      if (level == ConduitLog.Level.ERROR || level == ConduitLog.Level.WARN) {
        System.err.println(line);
        if (record.getThrown() != null) record.getThrown().printStackTrace(System.err);
      } else {
        System.out.println(line);
        if (record.getThrown() != null) record.getThrown().printStackTrace(System.out);
      }
    }

    @Override public void flush() {
      System.out.flush();
      System.err.flush();
    }

    @Override public void close() {
      flush();
    }

    /** JUL's seven levels onto Conduit's five. */
    private static ConduitLog.Level translate(Level level) {
      int value = level == null ? Level.INFO.intValue() : level.intValue();
      if (value >= Level.SEVERE.intValue()) return ConduitLog.Level.ERROR;
      if (value >= Level.WARNING.intValue()) return ConduitLog.Level.WARN;
      if (value >= Level.INFO.intValue()) return ConduitLog.Level.INFO;
      if (value >= Level.FINE.intValue()) return ConduitLog.Level.DEBUG;
      return ConduitLog.Level.TRACE;
    }

    /**
     * The last dot-separated segment, so a logger named for its class reads as the class and one
     * named "ViaVersion" stays "ViaVersion". The root logger's empty name gets no bracket at all.
     */
    private static String shortName(String name) {
      if (name == null || name.isBlank()) return "";
      int dot = name.lastIndexOf('.');
      return dot < 0 || dot == name.length() - 1 ? name : name.substring(dot + 1);
    }
  }
}
