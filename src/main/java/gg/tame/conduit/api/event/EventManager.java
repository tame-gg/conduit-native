package gg.tame.conduit.api.event;

import gg.tame.conduit.api.plugin.Plugin;

public interface EventManager {
  void register(Plugin plugin, Object listener);
  void unregister(Plugin plugin);
  <E extends Event> E fire(E event);
}
