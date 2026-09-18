package gg.tame.conduit.api.server;

import gg.tame.conduit.api.text.Text;
import java.util.Optional;

/**
 * The server-list answer the operator configured, before maintenance, the version gate or any
 * {@code ServerListPingEvent} listener changes it. {@code maxPlayers} is only the number the list
 * shows after the slash; it does not limit who can join. {@code favicon} is a
 * {@code data:image/png;base64,} URI.
 */
public record ServerListDefaults(Text description, int maxPlayers, Optional<String> favicon) {
  public ServerListDefaults {
    if (description == null) description = Text.empty();
    if (favicon == null) favicon = Optional.empty();
  }
}
