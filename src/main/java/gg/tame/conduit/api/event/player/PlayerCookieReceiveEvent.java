// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event.player;

import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.player.Player;

/**
 * The client answered a {@link Player#requestCookie} with the cookie it keeps under {@code key}, or with
 * none: {@code data} is then null. The answer ends at the proxy, since the backend never asked. A
 * backend's own cookie requests and the answers to them pass through without this event. Fired on the
 * player's connection thread, which waits for every listener: do not block.
 */
public record PlayerCookieReceiveEvent(Player player, String key, byte[] data) implements Event {}
