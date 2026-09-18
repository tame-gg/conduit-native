package gg.tame.conduit.api.event.player;

import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.api.server.RegisteredServer;
import java.util.Optional;

/**
 * The player moved from one backend to another. Follows {@link PlayerServerConnectedEvent} for
 * every switch, and is not fired for the first backend. Fired on the thread that ran the switch.
 */
public record PlayerServerSwitchEvent(Player player, Optional<RegisteredServer> source, RegisteredServer target) implements Event {}
