package gg.tame.conduit.login;

import java.util.List;
import java.util.UUID;

/** Authenticated identity once authentication is added; login-start identity is unverified. */
public record PlayerProfile(UUID uniqueId, String username, List<ProfileProperty> properties, boolean authenticated) {
  public PlayerProfile { properties = List.copyOf(properties); if (username.isBlank() || username.length() > 16) throw new IllegalArgumentException("invalid Minecraft username"); }
}
