package gg.tame.conduit.api.event.player;

import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.api.server.RegisteredServer;
import java.util.Optional;

public record PlayerServerSwitchFailedEvent(Player player, Optional<RegisteredServer> source, RegisteredServer target, String reason) implements Event {}
