package gg.tame.conduit.api.event.player;

import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.api.server.RegisteredServer;
import java.util.Optional;

/**
 * A switch that {@link PlayerServerConnectEvent} allowed did not complete: the backend refused
 * the player or timed out. The player is still on {@code source}, unless the client had already
 * been moved out of Play, in which case it is disconnected. Not fired for a cancelled switch, nor
 * for a first connection. Fired on the thread that ran the switch.
 */
public record PlayerServerSwitchFailedEvent(Player player, Optional<RegisteredServer> source, RegisteredServer target, String reason) implements Event {}
