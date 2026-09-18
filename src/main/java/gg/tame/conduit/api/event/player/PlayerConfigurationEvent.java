// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event.player;

import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.api.server.RegisteredServer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A 1.20.2+ client's Configuration phase for {@link #server()}: at its first join, and on every
 * switch that reconfigures it. Fired once per {@link Stage}, in order, on the thread running that
 * part of the connection. Only where Conduit relays the phase between a client and a server that
 * both have one without Via in between; when Via builds or translates the phase, or one side has
 * none, nothing is fired. A client before 1.20.2 has no Configuration phase and never gets one.
 */
public final class PlayerConfigurationEvent implements Event {
  public enum Stage {
    /** A switch only: the client is about to be asked to leave Play. The request waits for every listener. */
    ENTERING,
    /**
     * The client is in Configuration. From here until {@link #FINISHING} is over, a resource pack the
     * proxy offers the player is sent at once, as a Configuration packet, rather than once the client
     * has a world again; {@link #holdFinish} keeps the phase open while a listener works.
     */
    ENTERED,
    /** Every hold is over; the client is told to finish once every listener has returned. */
    FINISHING,
    /** The client has finished and is in Play. */
    FINISHED
  }

  /** Longest the proxy keeps a phase open for its holds, as a server waiting on the client gives up not long after. */
  public static final long MAX_HOLD_MILLIS = 12_000;

  private final Player player;
  private final RegisteredServer server;
  private final Stage stage;
  private final List<CompletionStage<?>> holds = new CopyOnWriteArrayList<>();

  public PlayerConfigurationEvent(Player player, RegisteredServer server, Stage stage) {
    this.player = player; this.server = server; this.stage = stage;
  }
  public Player player() { return player; }
  /** The server the client is being configured for: on a switch, the one it is moving to. */
  public RegisteredServer server() { return server; }
  public Stage stage() { return stage; }

  /**
   * {@link Stage#ENTERED} only: the phase does not finish before {@code until} has completed, normally
   * or not, and never for longer than {@link #MAX_HOLD_MILLIS} counted from this stage.
   */
  public void holdFinish(CompletionStage<?> until) {
    if (stage != Stage.ENTERED) throw new IllegalStateException("only an ENTERED configuration can be held, not " + stage);
    holds.add(java.util.Objects.requireNonNull(until, "until"));
  }
  /** What listeners asked the finish to wait for. */
  public List<CompletionStage<?>> holds() { return List.copyOf(holds); }
}
