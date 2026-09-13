package gg.tame.conduit.auth;

import gg.tame.conduit.config.AuthenticationMode;
import gg.tame.conduit.login.PlayerProfile;

public interface PlayerAuthenticator {
  AuthenticationMode mode();
  PlayerProfile verify(SessionQuery query) throws AuthenticationException;
}
