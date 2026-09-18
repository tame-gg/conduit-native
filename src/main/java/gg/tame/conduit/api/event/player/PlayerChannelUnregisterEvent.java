// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event.player;

import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.player.Player;
import java.util.List;

/**
 * The client unregistered plugin channels, on {@code minecraft:unregister} ({@code UNREGISTER} before
 * 1.13), bounded as {@link PlayerChannelRegisterEvent} is. The message still reaches the backend
 * unchanged. Fired on the player's connection thread, which waits for every listener: do not block.
 */
public record PlayerChannelUnregisterEvent(Player player, List<String> channels) implements Event {}
