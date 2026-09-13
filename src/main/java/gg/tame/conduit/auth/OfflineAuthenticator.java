package gg.tame.conduit.auth;

import gg.tame.conduit.config.AuthenticationMode;
import gg.tame.conduit.login.PlayerProfile;

/** Offline mode never contacts Mojang; identity remains the unverified Login Start profile. */
public final class OfflineAuthenticator implements PlayerAuthenticator {
  @Override public AuthenticationMode mode() { return AuthenticationMode.OFFLINE; }
  @Override public PlayerProfile verify(SessionQuery query) {
    throw new UnsupportedOperationException("offline mode does not perform session verification");
  }
}
