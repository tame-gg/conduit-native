// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.config;

/**
 * How players are identified. {@code kickExistingPlayers}: a login of a player who is already
 * connected ends the existing session and goes ahead, instead of being refused. Off by default: in
 * offline mode it would let anyone who types a name kick the player using it.
 */
public record AuthenticationSettings(AuthenticationMode mode, String sessionUrl, int timeoutMillis, boolean kickExistingPlayers) {
  public static final String DEFAULT_SESSION_URL = "https://sessionserver.mojang.com/session/minecraft/hasJoined";
  public AuthenticationSettings {
    if (sessionUrl == null || sessionUrl.isBlank()) throw new IllegalArgumentException("authentication.session-url is required");
    if (mode == AuthenticationMode.ONLINE && !sessionUrl.startsWith("https://") && !sessionUrl.startsWith("http://127.0.0.1") && !sessionUrl.startsWith("http://localhost")) {
      throw new IllegalArgumentException("authentication.session-url must use HTTPS");
    }
    if (timeoutMillis < 100 || timeoutMillis > 60_000) throw new IllegalArgumentException("authentication.timeout-millis must be 100..60000");
  }
  public AuthenticationSettings(AuthenticationMode mode, String sessionUrl, int timeoutMillis) {
    this(mode, sessionUrl, timeoutMillis, false);
  }
  public static AuthenticationSettings offline() {
    return new AuthenticationSettings(AuthenticationMode.OFFLINE, DEFAULT_SESSION_URL, 5_000);
  }
}
