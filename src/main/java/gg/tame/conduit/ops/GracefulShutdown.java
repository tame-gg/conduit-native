// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.ops;

import gg.tame.conduit.config.ShutdownSettings;
import gg.tame.conduit.log.ConduitLog;
import gg.tame.conduit.session.PlayerManager;
import gg.tame.conduit.session.TrackedPlayer;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Graceful shutdown: stop accepting, then disconnect every player with the shutdown message. */
public final class GracefulShutdown {
  private final AtomicBoolean shuttingDown = new AtomicBoolean();
  private volatile ShutdownSettings settings;
  /** What a plugin that stopped the proxy asked players to be told, instead of the configured message. */
  private volatile gg.tame.conduit.api.text.Text reason;

  public GracefulShutdown(ShutdownSettings settings) {
    this.settings = settings;
  }

  public void applySettings(ShutdownSettings replacement) { this.settings = replacement; }
  /** The live settings, so the listener can spend the same budget draining its connection workers. */
  public ShutdownSettings settings() { return settings; }
  public boolean isShuttingDown() { return shuttingDown.get(); }
  /** Players are kicked with {@code reason} rather than the configured message, if the shutdown has not begun. */
  public void kickWith(gg.tame.conduit.api.text.Text reason) { this.reason = reason; }
  /** What a player turned away by this shutdown is told. */
  public gg.tame.conduit.api.text.Text message() {
    gg.tame.conduit.api.text.Text asked = reason;
    return asked != null ? asked : gg.tame.conduit.api.text.Text.of(settings.message());
  }

  /**
   * Stops accepting and tells every player why they are being disconnected.
   *
   * <p>It used to move each player to a fallback backend first. Every fallback sits behind this same
   * proxy, so the move only bought a second login -- a burst of them at the fallback server, a
   * ServerConnectedEvent for plugins, the fallback world on screen behind the message -- before the
   * disconnect that followed anyway.
   */
  public void run(Runnable stopAccepting, PlayerManager players) {
    if (!shuttingDown.compareAndSet(false, true)) return;
    stopAccepting.run();
    disconnectAll(players, message());
    if (settings.gracefulEnabled()) ConduitLog.info("Graceful shutdown completed.");
  }

  private static void disconnectAll(PlayerManager players, gg.tame.conduit.api.text.Text message) {
    for (TrackedPlayer player : new ArrayList<>(players.all())) disconnect(player, message);
  }

  private static void disconnect(TrackedPlayer player, gg.tame.conduit.api.text.Text message) {
    if (player instanceof gg.tame.conduit.api.player.Player api) {
      try { api.disconnect(message); } catch (RuntimeException ignored) { }
    }
  }
}
