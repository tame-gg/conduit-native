// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.ops;

import gg.tame.conduit.config.ShutdownSettings;
import gg.tame.conduit.log.ConduitLog;
import gg.tame.conduit.routing.BackendSelector;
import gg.tame.conduit.session.PlayerManager;
import gg.tame.conduit.session.TrackedPlayer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded graceful shutdown: stop accepts, try fallback transfers, then disconnect. */
public final class GracefulShutdown {
  private final AtomicBoolean shuttingDown = new AtomicBoolean();
  private volatile ShutdownSettings settings;
  /** What a plugin that stopped the proxy asked players to be told, instead of the configured message. */
  private volatile gg.tame.conduit.api.text.Text reason;

  public GracefulShutdown(ShutdownSettings settings) {
    this.settings = settings;
  }

  public void applySettings(ShutdownSettings replacement) { this.settings = replacement; }
  public boolean isShuttingDown() { return shuttingDown.get(); }
  /** Players are kicked with {@code reason} rather than the configured message, if the shutdown has not begun. */
  public void kickWith(gg.tame.conduit.api.text.Text reason) { this.reason = reason; }
  /** What a player turned away by this shutdown is told. */
  public gg.tame.conduit.api.text.Text message() {
    gg.tame.conduit.api.text.Text asked = reason;
    return asked != null ? asked : gg.tame.conduit.api.text.Text.of(settings.message());
  }

  public void run(Runnable stopAccepting, PlayerManager players, BackendSelector selector) {
    if (!shuttingDown.compareAndSet(false, true)) return;
    stopAccepting.run();
    ShutdownSettings local = settings;
    gg.tame.conduit.api.text.Text message = message();
    if (!local.gracefulEnabled()) {
      disconnectAll(players, message);
      return;
    }
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(local.timeoutMs());
    List<TrackedPlayer> snapshot = new ArrayList<>(players.all());
    Semaphore permits = new Semaphore(16);
    List<Thread> workers = new ArrayList<>();
    for (TrackedPlayer player : snapshot) {
      workers.add(gg.tame.conduit.network.SocketThreads.start(() -> {
        try {
          permits.acquire();
          if (System.nanoTime() > deadline) {
            disconnect(player, message);
            return;
          }
          Set<String> failed = new HashSet<>();
          String current = player.currentBackend();
          if (current != null && !current.isBlank()) failed.add(current.toLowerCase());
          boolean moved = false;
          for (var server : selector.fallback(current == null ? "" : current, failed)) {
            if (System.nanoTime() > deadline) break;
            try {
              if (player.transferTo(server.name())) {
                moved = true;
                break;
              }
            } catch (RuntimeException ignored) { }
            failed.add(server.name().toLowerCase());
          }
          if (!moved) disconnect(player, message);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          disconnect(player, message);
        } finally {
          permits.release();
        }
      }));
    }
    for (Thread worker : workers) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) break;
      try { worker.join(TimeUnit.NANOSECONDS.toMillis(remaining) + 1); }
      catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    disconnectAll(players, message);
    ConduitLog.info("Graceful shutdown completed.");
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
