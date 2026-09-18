// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event.player;

import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.api.server.RegisteredServer;
import java.util.Optional;

/**
 * The player is now on {@code target}: after their first backend (with {@code source} empty, right
 * after {@link PlayerPostLoginEvent}, or once the Configuration phase has finished when that phase
 * raises {@link PlayerConfigurationEvent}) and after every successful switch. Fired on the thread
 * that ran the connection, or for that first phase on the one reading the client.
 */
public record PlayerServerConnectedEvent(Player player, Optional<RegisteredServer> source, RegisteredServer target) implements Event {}
