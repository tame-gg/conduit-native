package gg.tame.conduit.config;

public record AuthenticationSettings(AuthenticationMode mode, String sessionUrl, int timeoutMillis) {
  public static final String DEFAULT_SESSION_URL = "https://sessionserver.mojang.com/session/minecraft/hasJoined";
  public AuthenticationSettings {
    if (sessionUrl == null || sessionUrl.isBlank()) throw new IllegalArgumentException("authentication.session-url is required");
    if (mode == AuthenticationMode.ONLINE && !sessionUrl.startsWith("https://") && !sessionUrl.startsWith("http://127.0.0.1") && !sessionUrl.startsWith("http://localhost")) {
      throw new IllegalArgumentException("authentication.session-url must use HTTPS");
    }
    if (timeoutMillis < 100 || timeoutMillis > 60_000) throw new IllegalArgumentException("authentication.timeout-millis must be 100..60000");
  }
  public static AuthenticationSettings offline() {
    return new AuthenticationSettings(AuthenticationMode.OFFLINE, DEFAULT_SESSION_URL, 5_000);
  }
}
