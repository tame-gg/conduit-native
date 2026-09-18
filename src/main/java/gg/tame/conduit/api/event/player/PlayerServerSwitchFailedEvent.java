package gg.tame.conduit.api.event.player;

import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.api.server.RegisteredServer;
import java.util.Optional;

/**
 * A connection that {@link PlayerServerConnectEvent} allowed did not complete: the backend refused
 * the player, was unreachable or timed out. After a switch the player is still on {@code source},
 * unless the client had already been moved out of Play, in which case it is disconnected. On a first
 * connection {@code source} is empty and the next candidate server is tried. Not fired for a
 * cancelled connection. A backend that refused the login also fires
 * {@link PlayerKickedFromServerEvent}, right after this one, which decides what happens next.
 * Fired on the thread that ran the connection.
 */
public record PlayerServerSwitchFailedEvent(Player player, Optional<RegisteredServer> source, RegisteredServer target, String reason) implements Event {}
