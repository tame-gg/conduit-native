// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.player;

import gg.tame.conduit.api.text.Text;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An entry the proxy puts on one player's tab list, beside the backend's own. {@link #id} names it:
 * adding an entry with an id the proxy already shows updates that entry. An id the backend also uses
 * is the same entry on the client, so pick ids the backend does not.
 *
 * <p>Some fields only exist on newer clients, and an older one is simply not sent them: before 1.19.3
 * every entry is listed, list order arrived in 1.21.2 and the hat toggle in 1.21.4.
 *
 * @param name        the profile name, at most 16 characters
 * @param properties  profile properties, such as a signed {@code textures} for a skin
 * @param displayName shown instead of {@code name}; null for none
 * @param latency     milliseconds, which the client draws as signal bars
 * @param gameMode    0 survival, 1 creative, 2 adventure, 3 spectator (shown greyed)
 * @param listed      whether it appears on the list at all
 * @param listOrder   higher sorts first
 * @param showHat     whether the skin's hat layer is drawn
 */
public record TabListEntry(UUID id, String name, List<Property> properties, Text displayName, int latency, int gameMode,
                           boolean listed, int listOrder, boolean showHat) {
  /** A profile property; {@code signature} is null when unsigned. */
  public record Property(String name, String value, String signature) {
    public Property {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(value, "value");
    }
  }

  public TabListEntry {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(name, "name");
    if (name.length() > 16) throw new IllegalArgumentException("a profile name is at most 16 characters: " + name);
    if (gameMode < 0 || gameMode > 3) throw new IllegalArgumentException("game mode must be 0-3, was " + gameMode);
    properties = properties == null ? List.of() : List.copyOf(properties);
  }
}
