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

  public GracefulShutdown(ShutdownSettings settings) {
    this.settings = settings;
  }

  public void applySettings(ShutdownSettings replacement) { this.settings = replacement; }
  public boolean isShuttingDown() { return shuttingDown.get(); }

  public void run(Runnable stopAccepting, PlayerManager players, BackendSelector selector) {
    if (!shuttingDown.compareAndSet(false, true)) return;
    stopAccepting.run();
    ShutdownSettings local = settings;
    if (!local.gracefulEnabled()) {
      disconnectAll(players, local.message());
      return;
    }
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(local.timeoutMs());
    List<TrackedPlayer> snapshot = new ArrayList<>(players.all());
    Semaphore permits = new Semaphore(16);
    List<Thread> workers = new ArrayList<>();
    for (TrackedPlayer player : snapshot) {
      workers.add(Thread.startVirtualThread(() -> {
        try {
          permits.acquire();
          if (System.nanoTime() > deadline) {
            disconnect(player, local.message());
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
          if (!moved) disconnect(player, local.message());
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          disconnect(player, local.message());
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
    disconnectAll(players, local.message());
    ConduitLog.info("Graceful shutdown completed.");
  }

  private static void disconnectAll(PlayerManager players, String message) {
    for (TrackedPlayer player : new ArrayList<>(players.all())) disconnect(player, message);
  }

  private static void disconnect(TrackedPlayer player, String message) {
    if (player instanceof gg.tame.conduit.api.player.Player api) {
      try { api.disconnect(message); } catch (RuntimeException ignored) { }
    }
  }
}
