// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.player;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A player's identity as backends are told it: UUID, name and profile properties -- above all
 * {@code textures}, the signed skin and cape. For an online-mode player these come from the session
 * server; a {@code GameProfileRequestEvent} listener may have replaced them.
 *
 * <p>The name follows Conduit's rule for a name a client may log in with: 1 to 16 characters from
 * {@code !} to {@code ~}. Signatures are carried as given; Conduit never checks them.
 */
public record GameProfile(UUID uniqueId, String name, List<Property> properties) {
  public GameProfile {
    Objects.requireNonNull(uniqueId, "uniqueId");
    if (name == null || name.isEmpty() || name.length() > 16 || !name.chars().allMatch(c -> c > ' ' && c < 0x7f)) {
      throw new IllegalArgumentException("not a name a player can have: " + name);
    }
    properties = List.copyOf(properties == null ? List.of() : properties);
  }
  /** One profile property: its name, its value (Base64 for textures), and Mojang's signature if it has one. */
  public record Property(String name, String value, Optional<String> signature) {
    public Property {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(value, "value");
      signature = signature == null ? Optional.empty() : signature;
    }
  }
}
