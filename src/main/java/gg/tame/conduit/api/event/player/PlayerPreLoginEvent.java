// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event.player;

import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.text.Text;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

/**
 * A client has asked to log in, and nothing about it has been verified yet: fired once its Login
 * Start is read, before online-mode authentication. The handshake, the protocol and the version gate
 * have already been checked by the proxy; the name has passed Conduit's rule for names (1 to 16
 * characters from {@code !} to {@code ~}) but is only what the client claims.
 *
 * <p>{@link #deny} refuses the login there and then, with the reason on the client's disconnect
 * screen: no encryption, no session-server round trip, no {@link PlayerSetupEvent} and no
 * {@link PlayerDisconnectEvent}, because no Player is ever created.
 *
 * <p>{@link #setAuthentication} chooses how this one connection is authenticated:
 * <ul>
 *   <li>{@link Authentication#PROXY_DEFAULT}: as {@code [authentication] mode} says.
 *   <li>{@link Authentication#FORCE_OFFLINE}: on an online-mode proxy, the client is not asked to
 *       encrypt and is not checked with the session server. The player is then exactly an offline
 *       player: {@code authenticated()} is false, the name is only claimed, and the UUID is the one the
 *       client sent (1.20.2 and later) or the one derived from the name. That profile is what
 *       forwarding tells the backends. Use it only for a client that is verified some other way.
 *   <li>{@link Authentication#FORCE_ONLINE}: on an offline-mode proxy, the client is authenticated as
 *       on an online-mode one, against the configured {@code session-url}; a failure refuses it. The
 *       session URL must be HTTPS or a loopback address for this, as online mode requires, or the
 *       login is refused.
 * </ul>
 * On a proxy already in that mode each is the same as {@code PROXY_DEFAULT}. Neither changes
 * anything else: the conflicting-login check, maintenance and PlayerLoginEvent still follow.
 *
 * <p>Fired on the connection's own thread, which holds the login until every listener returns.
 */
public final class PlayerPreLoginEvent implements Event {
  /** How the connection is authenticated. */
  public enum Authentication { PROXY_DEFAULT, FORCE_ONLINE, FORCE_OFFLINE }

  private final String username;
  private final Optional<UUID> claimedUniqueId;
  private final InetAddress remoteAddress;
  private final InetSocketAddress virtualHost;
  private final int protocolVersion;
  private final boolean transferred;
  private final BiFunction<String, byte[], CompletableFuture<byte[]>> loginMessages;
  private volatile Text denied;
  private volatile Authentication authentication = Authentication.PROXY_DEFAULT;

  public PlayerPreLoginEvent(String username, Optional<UUID> claimedUniqueId, InetAddress remoteAddress,
                             InetSocketAddress virtualHost, int protocolVersion, boolean transferred) {
    this(username, claimedUniqueId, remoteAddress, virtualHost, protocolVersion, transferred, (channel, data) -> {
      throw new IllegalStateException("no client login to send a login plugin message to");
    });
  }
  public PlayerPreLoginEvent(String username, Optional<UUID> claimedUniqueId, InetAddress remoteAddress,
                             InetSocketAddress virtualHost, int protocolVersion, boolean transferred,
                             BiFunction<String, byte[], CompletableFuture<byte[]>> loginMessages) {
    this.username = username; this.claimedUniqueId = claimedUniqueId; this.remoteAddress = remoteAddress;
    this.virtualHost = virtualHost; this.protocolVersion = protocolVersion; this.transferred = transferred;
    this.loginMessages = loginMessages;
  }
  /**
   * Sends the client a Login Plugin Request on {@code channel} (a namespaced key; {@code minecraft:}
   * when it has none) with {@code data}, and completes with the client's answer: its bytes, or null
   * when the client did not understand the channel.
   *
   * <p>Nothing is written at once. A request made in this event is sent once the proxy has
   * authenticated the client (after encryption, in online mode); one made later in the login --
   * from {@link GameProfileRequestEvent} to {@link PlayerLoginEvent}, by keeping this event -- once
   * PlayerLoginEvent has let the login go on. At each point the requests go out in the order they were
   * made, and the login waits for every answer, within the login's own deadline, before it goes
   * further. The future completes on the connection's thread, so what depends on it must not block;
   * it completes exceptionally when the login ends first. Once PlayerLoginEvent has been decided no
   * more can be sent.
   *
   * @throws IllegalStateException for a client before 1.13, which has no login plugin messages, or once the login has been decided
   * @throws IllegalArgumentException for a channel that is not a namespaced key, or more than 1 MiB of data
   */
  public CompletableFuture<byte[]> sendLoginPluginMessage(String channel, byte[] data) { return loginMessages.apply(channel, data); }
  /** The name in the client's Login Start, unverified. */
  public String username() { return username; }
  /** The UUID in the client's Login Start, unverified; empty for a client whose Login Start carries none. */
  public Optional<UUID> claimedUniqueId() { return claimedUniqueId; }
  /** As {@code Player.remoteAddress()} will be: the socket's peer, or the configured forwarded address. */
  public InetAddress remoteAddress() { return remoteAddress; }
  /** Host and port the client says it dialled, unresolved, Forge markers removed. */
  public InetSocketAddress virtualHost() { return virtualHost; }
  public int protocolVersion() { return protocolVersion; }
  /** Whether the client arrived by a 1.20.5+ Transfer packet from another server (handshake intent 3). */
  public boolean transferred() { return transferred; }

  public void deny(Text reason) { this.denied = reason == null ? Text.empty() : reason; }
  public void deny(String reason) { deny(Text.of(reason == null ? "" : reason)); }
  public void allow() { this.denied = null; }
  public boolean allowed() { return denied == null; }
  public Optional<Text> denyReason() { return Optional.ofNullable(denied); }

  public Authentication authentication() { return authentication; }
  public void setAuthentication(Authentication authentication) {
    this.authentication = authentication == null ? Authentication.PROXY_DEFAULT : authentication;
  }
}
