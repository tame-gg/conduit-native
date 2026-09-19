// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.log;

/**
 * ANSI colours for the proxy's own console: white for ordinary lines, yellow for warnings, red for
 * errors, and dim grey for the debug and trace lines that are opt-in anyway.
 *
 * <p>Colour is off when it would end up somewhere that cannot show it. A redirected stream is the
 * common case -- {@code conduit.log} full of escape sequences is worse than a plain one -- and
 * {@code System.console()} is null exactly then, so it is what the decision rests on. The two
 * conventions operators already know are honoured: {@code NO_COLOR} with any value turns it off
 * (no-color.org), and {@code TERM=dumb} means the terminal cannot do it. {@code -Dconduit.color}
 * overrides all of that in either direction, which is what a CI log or a stubborn terminal needs.
 *
 * <p>On Windows this needs virtual-terminal processing, which Windows Terminal and PowerShell 7
 * switch on for themselves and the console host behind a double-clicked {@code .bat} does not. In
 * that console Conduit switches it on itself ({@link WindowsConsole}); when that cannot be done the
 * escapes would print as literal text, so colour is left off rather than written into a console
 * that would show it as junk.
 */
public final class ConduitColors {
  private static final String RESET = "[0m";
  private static final String WHITE = "[97m";
  private static final String YELLOW = "[93m";
  private static final String RED = "[91m";
  private static final String GREY = "[90m";

  private static final boolean ENABLED = decide();

  private ConduitColors() {}

  public static boolean enabled() {
    return ENABLED;
  }

  /** The line, coloured for its level, or the line unchanged when colour is off. */
  public static String paint(ConduitLog.Level level, String line) {
    if (!ENABLED) return line;
    return switch (level) {
      case ERROR -> RED + line + RESET;
      case WARN -> YELLOW + line + RESET;
      case INFO -> WHITE + line + RESET;
      case DEBUG, TRACE -> GREY + line + RESET;
    };
  }

  /**
   * Whether the console is a terminal rather than a file or a pipe, which is when an escape
   * sequence becomes literal junk in someone's log.
   *
   * <p>{@code System.console() == null} used to answer this on its own. Since Java 22 it does not:
   * a Console is handed out even for a redirected stream, and {@code isTerminal()} is what draws
   * the line now. That method cannot be named while compiling against 21, so it is called
   * reflectively when it is there and the old null check stands in when it is not.
   */
  private static boolean attachedToTerminal() {
    java.io.Console console = System.console();
    if (console == null) return false;
    try {
      return (boolean) java.io.Console.class.getMethod("isTerminal").invoke(console);
    } catch (ReflectiveOperationException olderRuntime) {
      return true;
    }
  }

  private static boolean decide() {
    String override = System.getProperty("conduit.color");
    if (override != null && !override.isBlank()) return Boolean.parseBoolean(override);
    if (System.getenv("NO_COLOR") != null) return false;
    if ("dumb".equalsIgnoreCase(System.getenv("TERM"))) return false;
    if (!attachedToTerminal()) return false;
    if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows")) {
      return true;
    }
    // A Windows terminal that does ANSI on its own behalf says so in the environment; anything else
    // has to be switched over, and colour is only claimed when that worked.
    if (System.getenv("WT_SESSION") != null
        || "ON".equalsIgnoreCase(String.valueOf(System.getenv("ConEmuANSI")))
        || System.getenv("ANSICON") != null
        || System.getenv("TERM") != null) {
      return true;
    }
    return WindowsConsole.enableVirtualTerminal();
  }
}
