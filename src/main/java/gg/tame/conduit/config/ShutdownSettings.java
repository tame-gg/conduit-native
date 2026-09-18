// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.config;

/** Graceful proxy shutdown behavior. */
public record ShutdownSettings(boolean gracefulEnabled, int timeoutMs, String message) {
  public static final String DEFAULT_MESSAGE = "Conduit is shutting down.";

  public ShutdownSettings {
    if (timeoutMs < 100) throw new IllegalArgumentException("shutdown.timeout-ms must be >= 100");
    if (message == null || message.isBlank()) message = DEFAULT_MESSAGE;
  }

  public static ShutdownSettings defaults() {
    return new ShutdownSettings(true, 5_000, DEFAULT_MESSAGE);
  }
}
