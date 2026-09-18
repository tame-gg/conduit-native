// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event.player;

import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.api.server.RegisteredServer;
import java.util.Optional;

/**
 * A backend the player is logging in to -- their first server, or one they switch or fall back to --
 * sent a Login Plugin Request the proxy has no answer of its own for: anything but modern forwarding's
 * {@code velocity:player_info}, which never raises this. {@link #reply} answers it for the player: the
 * backend gets those bytes as an understood answer, and the client never sees the request.
 *
 * <p>Left unanswered, the proxy does what it always did. During the player's first login, while the
 * client is still logging in, the request goes on to the client, and the client's answer, understood or
 * not, back to the backend. Later, on a switch or a fallback, the client has left its login and cannot
 * answer, so that connection fails. Fired on the thread logging in to the backend, which waits for
 * every listener: do not block.
 */
public final class BackendLoginPluginMessageEvent implements Event {
  private final Player player;
  private final RegisteredServer server;
  private final String channel;
  private final byte[] data;
  private final int messageId;
  private volatile byte[] reply;

  public BackendLoginPluginMessageEvent(Player player, RegisteredServer server, String channel, byte[] data, int messageId) {
    this.player = player; this.server = server; this.channel = channel; this.data = data.clone(); this.messageId = messageId;
  }
  public Player player() { return player; }
  /** The backend asking; null for one no longer registered with the proxy. */
  public RegisteredServer server() { return server; }
  public String channel() { return channel; }
  public byte[] data() { return data.clone(); }
  /** The backend's id for this request, which its answer carries. */
  public int messageId() { return messageId; }
  /** Answers the request with {@code data}; null takes an answer back, leaving the request as it would go. */
  public void reply(byte[] data) { this.reply = data == null ? null : data.clone(); }
  public Optional<byte[]> reply() { return Optional.ofNullable(reply).map(byte[]::clone); }
}
