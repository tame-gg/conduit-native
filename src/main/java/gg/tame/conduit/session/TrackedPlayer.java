// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.session;

import java.util.UUID;

/** Live player index entry. currentBackend must come from the session, not a copy. */
public interface TrackedPlayer {
  UUID uniqueId();
  String username();
  String currentBackend();
  boolean transferTo(String serverName);
}
