// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event.player;

import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.player.Player;
import java.util.List;

/**
 * The client registered plugin channels, on {@code minecraft:register} ({@code REGISTER} before 1.13):
 * how a client announces the mods and plugins it speaks for. {@code channels} holds the names in that
 * one message, at most 256 of them, each at most 256 characters; blank and longer names are left out,
 * and a message naming none raises no event. The message still reaches the backend unchanged. Not
 * fired for what the client sends while a server switch is under way. Fired on the player's
 * connection thread, which waits for every listener: do not block.
 */
public record PlayerChannelRegisterEvent(Player player, List<String> channels) implements Event {}
