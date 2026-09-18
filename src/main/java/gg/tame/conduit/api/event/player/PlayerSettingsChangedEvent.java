// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event.player;

import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.player.Player;

/**
 * The client sent its settings: once as it joins, and again whenever the player changes them.
 * {@link Player#locale()} answers with what it sent. Fired on the player's connection thread, which
 * waits for every listener: do not block.
 */
public record PlayerSettingsChangedEvent(Player player) implements Event {}
