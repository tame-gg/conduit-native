// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event.player;

import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.player.Player;

/**
 * A player has gone, for any reason. Every player that got {@link PlayerSetupEvent} gets this exactly
 * once, whether the proxy refused their login, they left while it was being decided, no server would
 * take them, or they played and left; release here whatever was kept for them. They are no longer in
 * the player lookup. Fired on the connection's thread.
 *
 * <p>{@link #loginStatus()} says how far the login got. {@link #completedLogin()} is whether
 * {@link PlayerPostLoginEvent} fired for them.
 */
public record PlayerDisconnectEvent(Player player, LoginStatus loginStatus) implements Event {
  /** How far the player's login got before they went. */
  public enum LoginStatus {
    /** They reached a server ({@link PlayerPostLoginEvent} fired) and have now left. */
    SUCCESSFUL_LOGIN,
    /** {@link PlayerLoginEvent} let them in, but no server took them, or they left or were kicked while being connected. */
    PRE_SERVER_JOIN,
    /**
     * The proxy refused the login: maintenance mode, a denied {@link PlayerLoginEvent}, a listener
     * that kicked them during the login, or the proxy shutting down.
     */
    CANCELLED_BY_PROXY,
    /** The client hung up while its login was being decided, before any server was contacted. */
    CANCELLED_BY_USER
  }

  public PlayerDisconnectEvent {
    if (loginStatus == null) throw new IllegalArgumentException("loginStatus is required");
  }
  /** A player who got {@link PlayerPostLoginEvent}. */
  public PlayerDisconnectEvent(Player player) { this(player, LoginStatus.SUCCESSFUL_LOGIN); }
  /** A player who got {@link PlayerPostLoginEvent} (true), or one let in whom no server took (false). */
  public PlayerDisconnectEvent(Player player, boolean completedLogin) {
    this(player, completedLogin ? LoginStatus.SUCCESSFUL_LOGIN : LoginStatus.PRE_SERVER_JOIN);
  }
  /** Whether {@link PlayerPostLoginEvent} fired for them. */
  public boolean completedLogin() { return loginStatus == LoginStatus.SUCCESSFUL_LOGIN; }
}
