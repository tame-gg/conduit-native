// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.text;

import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.log.ConduitLog;
import java.lang.reflect.Method;

/**
 * MiniMessage, when the jar it is part of is on the class path.
 *
 * <p>Adventure comes with the Velocity plugin runtime, and core is compiled without it so that a
 * build of Conduit alone still builds. The adapter that does see Adventure is found by name, the
 * way {@code MinecraftProxy} finds {@code VelocityBoot}: a class path assembled without it gets
 * plain text back and everything that took {@code &} codes before still takes them.
 */
public final class MiniMessages {
  private MiniMessages() {}

  private static final Method PARSE = find();
  /** MiniMessage failing on one string is worth saying once, not once per status ping. */
  private static volatile boolean complained;

  private static Method find() {
    try {
      return Class.forName("gg.tame.conduit.compat.velocity.MiniMessageText").getMethod("parse", String.class);
    } catch (ReflectiveOperationException | LinkageError absent) {
      return null;
    }
  }

  /** Whether MiniMessage can be reached at all. */
  public static boolean available() { return PARSE != null; }

  /**
   * {@code raw} as MiniMessage reads it, or null when MiniMessage is not here or could not read it
   * -- a malformed tag costs a MOTD its colours, never the server its listing.
   */
  public static Text parse(String raw) {
    if (PARSE == null || raw == null) return null;
    try {
      return (Text) PARSE.invoke(null, raw);
    } catch (ReflectiveOperationException | RuntimeException | LinkageError failed) {
      if (!complained) {
        complained = true;
        ConduitLog.warn("MiniMessage could not read \"" + raw + "\", so it is used as it is: "
            + (failed.getCause() == null ? failed : failed.getCause()));
      }
      return null;
    }
  }
}
