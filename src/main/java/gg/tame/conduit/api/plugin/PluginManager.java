package gg.tame.conduit.api.plugin;

import java.util.Collection;
import java.util.Optional;

public interface PluginManager {
  Collection<Plugin> plugins();
  Optional<Plugin> plugin(String id);
  void disable(Plugin plugin);
}
