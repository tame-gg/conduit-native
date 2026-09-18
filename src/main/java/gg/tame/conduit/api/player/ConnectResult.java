// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.player;

/** How a {@link Player#connectWithResult} ended. {@code reason} is empty unless the status is a failure. */
public record ConnectResult(Status status, String reason) {
  public enum Status {
    /** The player is now on the server. */
    CONNECTED,
    /** The player was already on it; nothing happened. */
    ALREADY_CONNECTED,
    /** Another switch for this player was still running; nothing happened. */
    IN_PROGRESS,
    /** A {@code PlayerServerConnectEvent} listener cancelled it; no backend connection was opened. */
    CANCELLED,
    /** The server was unknown, refused the player, or the switch timed out. The player stays where they were. */
    FAILED
  }
  public ConnectResult {
    if (status == null) throw new IllegalArgumentException("status is required");
    reason = reason == null ? "" : reason;
  }
  public boolean successful() { return status == Status.CONNECTED; }
}
