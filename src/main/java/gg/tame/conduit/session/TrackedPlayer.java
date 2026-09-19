// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.session;

import java.util.UUID;

/** Live player index entry. currentBackend must come from the session, not a copy. */
public interface TrackedPlayer {
  UUID uniqueId();
  String username();
  String currentBackend();
  boolean transferTo(String serverName);

  /**
   * Sends the client its command tree again, for a 1.13+ client whose tree would otherwise stay as
   * the backend last declared it until the next server switch. Nothing to do for an entry that is
   * not a live session.
   */
  default void refreshCommands() {}
}
