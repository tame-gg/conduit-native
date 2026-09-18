package gg.tame.conduit.api.event.player;

import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.player.Player;

/** An online-mode player was allowed in: fired right after an allowed {@link PlayerLoginEvent}, on the same thread. */
public record PlayerAuthenticatedEvent(Player player) implements Event {}
