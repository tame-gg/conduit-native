package gg.tame.conduit.api.player;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface PlayerLookup {
  Optional<Player> get(UUID uniqueId);
  Optional<Player> getByUsername(String username);
  Collection<Player> all();
}
