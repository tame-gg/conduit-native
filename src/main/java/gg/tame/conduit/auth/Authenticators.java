// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.auth;

import gg.tame.conduit.config.AuthenticationMode;
import gg.tame.conduit.config.AuthenticationSettings;

public final class Authenticators {
  private Authenticators() { }
  public static PlayerAuthenticator create(AuthenticationSettings settings) {
    if (settings.mode() == AuthenticationMode.OFFLINE) return new OfflineAuthenticator();
    return new MojangSessionAuthenticator(settings);
  }
}
