// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event.player;

import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.api.text.Text;
import java.util.Optional;

/**
 * A client finished logging in to the proxy (and, in online mode, authenticating) and is about to
 * be sent to its first backend. Maintenance and version checks have already passed.
 *
 * <p>{@link #deny} refuses it: the client is shown the reason on its disconnect screen and no
 * backend connection is ever opened. Fired on the connection's own thread, which holds the login
 * open until every listener returns.
 */
public final class PlayerLoginEvent implements Event {
  private final Player player;
  private volatile Text denied;
  public PlayerLoginEvent(Player player) { this.player = player; }
  public Player player() { return player; }
  public void deny(Text reason) { this.denied = reason == null ? Text.empty() : reason; }
  public void deny(String reason) { deny(Text.of(reason == null ? "" : reason)); }
  public void allow() { this.denied = null; }
  public boolean allowed() { return denied == null; }
  public Optional<Text> denyReason() { return Optional.ofNullable(denied); }
}
