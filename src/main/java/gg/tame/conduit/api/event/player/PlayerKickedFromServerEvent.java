package gg.tame.conduit.api.event.player;

import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.api.server.RegisteredServer;
import gg.tame.conduit.api.text.Text;
import java.util.Objects;
import java.util.Optional;

/**
 * A backend disconnected the player: the server they were playing on ({@code duringConnect} false),
 * or one they were being connected to that refused their login ({@code duringConnect} true) -- a
 * switch, their first server, or a fallback after their server was lost. A backend that simply goes
 * away, with no disconnect packet, is not a kick; the player is moved to a fallback server instead.
 *
 * <p>{@link #result()} says what happens next, and starts as what Conduit would do anyway:
 * <ul>
 *   <li>Kicked from the server they were on: {@link KickResult.Disconnect} with the backend's
 *       reason, which the player sees exactly as the backend wrote it. {@link KickResult.Notify}
 *       there is the same as Disconnect with its message, because there is nowhere left to stay.
 *   <li>Refused during a switch: {@link KickResult.Notify} with the backend's reason; the player
 *       stays where they were and is shown it in chat.
 *   <li>Refused by their first server, or by a fallback: {@link KickResult.Notify} with the reason.
 *       The next candidate server is tried, and the message is what the player's disconnect screen
 *       says if none of them takes them. Disconnect ends the attempt at once.
 * </ul>
 * {@link KickResult.Redirect} sends the player to another registered server instead. Kicked from the
 * server they were on, they are moved there, and disconnected with the backend's reason if that
 * fails too. Refused during a switch, that server is tried instead, and if it fails they stay where
 * they were and are shown the backend's reason. On a first connection or a fallback it is the next
 * server tried. Its message is shown in chat once they arrive, except on a first connection, which
 * has no chat yet.
 *
 * <p>A refusal that arrives after a switching client has already been moved into the new server's
 * configuration phase is relayed to the client as it is and not reported here: by then there is no
 * way back. Fired on the thread that read the kick: the player's backend reader when they were
 * playing, the thread running the connection otherwise. Do not block.
 */
public final class PlayerKickedFromServerEvent implements Event {
  /** What happens to a kicked player. */
  public sealed interface KickResult {
    /** Disconnect the player, showing {@code reason}. */
    record Disconnect(Text reason) implements KickResult {
      public Disconnect { Objects.requireNonNull(reason, "reason"); }
    }
    /** Send the player to {@code server}, which must be registered with the proxy. */
    record Redirect(RegisteredServer server, Optional<Text> message) implements KickResult {
      public Redirect {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(message, "message");
      }
    }
    /** Keep the player where they are and tell them {@code message}. */
    record Notify(Text message) implements KickResult {
      public Notify { Objects.requireNonNull(message, "message"); }
    }
  }

  private final Player player;
  private final RegisteredServer server;
  private final Optional<Text> reason;
  private final boolean duringConnect;
  private volatile KickResult result;

  public PlayerKickedFromServerEvent(Player player, RegisteredServer server, Optional<Text> reason, boolean duringConnect,
                                     KickResult result) {
    this.player = player; this.server = server; this.reason = reason; this.duringConnect = duringConnect;
    setResult(result);
  }
  public Player player() { return player; }
  /** The server that kicked the player. */
  public RegisteredServer server() { return server; }
  /**
   * The reason the backend gave, read from its disconnect packet. Text has no translatable parts, so
   * a translated reason arrives as its translation key; a disconnect screen Conduit shows by default
   * still carries the backend's own component. Empty when the packet could not be read.
   */
  public Optional<Text> reason() { return reason; }
  /** True when the player was being connected to {@link #server()}, not playing on it. */
  public boolean duringConnect() { return duringConnect; }
  public KickResult result() { return result; }
  /** Refuses null, so a listener that passes one fails without leaving the player nowhere. */
  public void setResult(KickResult result) { this.result = Objects.requireNonNull(result, "result"); }
}
