// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event.player;

import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.player.Player;

/**
 * The client named itself on the brand channel ({@code minecraft:brand}, {@code MC|Brand} before
 * 1.13): "vanilla", "fabric", "forge" and so on, as the client chose to say. The brand still goes on
 * to the backend. Fired on the player's connection thread, which waits for every listener: do not block.
 */
public record PlayerClientBrandEvent(Player player, String brand) implements Event {}
