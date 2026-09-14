package gg.tame.conduit.login;

/**
 * Canonical post-authentication identity. This is {@link PlayerProfile} after Mojang
 * hasJoined (or an explicit offline profile). Backend connections must reference
 * the session copy; they must not allocate an empty profile on switch.
 */
public final class AuthenticatedPlayerProfile {
  private AuthenticatedPlayerProfile() {}
  public static PlayerProfile require(PlayerProfile profile) {
    if (profile == null) throw new IllegalStateException("authenticated profile missing");
    return profile;
  }
  public static PlayerProfile freeze(PlayerProfile current, PlayerProfile next) {
    if (current.authenticated()) {
      if (!current.uniqueId().equals(next.uniqueId()) || !current.username().equals(next.username())) {
        throw new IllegalStateException("authenticated UUID/username cannot change");
      }
      return current;
    }
    return next;
  }
}