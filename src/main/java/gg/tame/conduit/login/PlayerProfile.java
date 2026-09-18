// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.login;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Protocol-independent identity. Login Start is unverified; hasJoined is authoritative when authenticated. */
public record PlayerProfile(UUID uniqueId, String username, List<ProfileProperty> properties, boolean authenticated) {
  public PlayerProfile {
    properties = List.copyOf(properties);
    if (username.isBlank() || username.length() > 16) throw new IllegalArgumentException("invalid Minecraft username");
  }
  public Optional<ProfileProperty> property(String name) {
    return properties.stream().filter(property -> property.name().equals(name)).findFirst();
  }
  public boolean hasTextures() { return property("textures").isPresent(); }
  /** Safe for logs: never includes property values or signatures. */
  public String summary() {
    Optional<ProfileProperty> textures = property("textures");
    return "Profile: UUID=" + uniqueId + " Name=" + username + " Authenticated=" + authenticated
        + " Properties=" + properties.size()
        + " textures=" + (textures.isPresent() ? "present" : "absent")
        + " textures.signed=" + (textures.flatMap(ProfileProperty::signature).isPresent() ? "yes" : "no");
  }
}
