package gg.tame.conduit.api;

import gg.tame.conduit.api.command.CommandManager;
import gg.tame.conduit.api.event.EventManager;
import gg.tame.conduit.api.permission.PermissionProvider;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.api.player.PlayerLookup;
import gg.tame.conduit.api.plugin.PluginManager;
import gg.tame.conduit.api.scheduler.Scheduler;
import gg.tame.conduit.api.server.ServerManager;
import java.util.Optional;

/** Stable native proxy entry point. Does not expose sockets, ciphers, or forwarding secrets. */
public interface ConduitProxy {
  String version();
  int apiVersion();
  PlayerLookup players();
  ServerManager servers();
  CommandManager commands();
  EventManager events();
  PluginManager plugins();
  Scheduler scheduler();
  PermissionProvider permissions();
  Optional<Player> player(java.util.UUID uniqueId);
  Optional<Player> player(String username);
}
