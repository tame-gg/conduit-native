// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event.player;

import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.api.text.Text;
import java.util.Optional;

/**
 * A client finished logging in to the proxy (and, in online mode, authenticating) and is about to
 * be sent to its first backend. {@link PlayerSetupEvent} has fired, so permission plugins know the
 * player, and the version gate and maintenance mode have already let them through.
 *
 * <p>{@link #deny} refuses it: the client is shown the reason on its disconnect screen and no
 * backend connection is ever opened. Fired on the connection's own thread, which holds the login
 * open until every listener returns. A denied login gets a {@link PlayerDisconnectEvent} with
 * {@code CANCELLED_BY_PROXY}.
 *
 * <p>A player that maintenance mode refuses never gets this event: they are refused before it, and
 * get their {@link PlayerDisconnectEvent} straight away. Maintenance is not handed to listeners as a
 * denied event they may {@link #allow}, because a listener that allows every login would then open
 * a network the operator closed. To let someone in during maintenance, grant them
 * {@code conduit.maintenance.bypass} (or {@code conduit.admin}) through the permission provider,
 * which is asked after {@link PlayerSetupEvent}, or add their name to the maintenance allowlist.
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
