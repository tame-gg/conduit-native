// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event.player;

import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.player.GameProfile;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * The profile a login will have has been settled -- by the session server for an online-mode
 * player, from the client's claim otherwise -- and a plugin may replace it: a different skin, a
 * different name, a different UUID. Fired after {@link PlayerPreLoginEvent} and authentication, before
 * the conflicting-login check and {@link PlayerSetupEvent}.
 *
 * <p>The profile left here is the player's from then on: {@code Player.uniqueId()},
 * {@code username()} and {@code gameProfile()} report it, it is what forwarding tells every backend
 * (and, with forwarding {@code none}, the name and UUID in the backend's Login Start), and it is the
 * client's own entry in the tab list Conduit writes. What does not change: {@code authenticated()}
 * still says whether the session server vouched for the connection, and the account that logged in
 * still counts for the one-session-per-player check alongside the replacement, so neither a second
 * login of the same account nor another account replaced into a connected player's UUID or name gets
 * in. Signatures are passed on as given; a property with a signature Mojang did not make is refused by
 * clients and servers that check it.
 *
 * <p>Nothing is guaranteed to follow for this login -- it may still be refused as a duplicate before
 * PlayerSetupEvent -- so keep nothing per player here. Fired on the connection's own thread.
 */
public final class GameProfileRequestEvent implements Event {
  private final String username;
  private final InetAddress remoteAddress;
  private final InetSocketAddress virtualHost;
  private final int protocolVersion;
  private final boolean transferred;
  private final boolean onlineMode;
  private final GameProfile originalProfile;
  private volatile GameProfile gameProfile;

  public GameProfileRequestEvent(String username, InetAddress remoteAddress, InetSocketAddress virtualHost, int protocolVersion,
                                 boolean transferred, boolean onlineMode, GameProfile originalProfile) {
    this.username = username; this.remoteAddress = remoteAddress; this.virtualHost = virtualHost;
    this.protocolVersion = protocolVersion; this.transferred = transferred; this.onlineMode = onlineMode;
    this.originalProfile = originalProfile; this.gameProfile = originalProfile;
  }
  /** The name the client logged in with. */
  public String username() { return username; }
  public InetAddress remoteAddress() { return remoteAddress; }
  public InetSocketAddress virtualHost() { return virtualHost; }
  public int protocolVersion() { return protocolVersion; }
  public boolean transferred() { return transferred; }
  /** Whether the session server vouched for this connection. */
  public boolean onlineMode() { return onlineMode; }
  public GameProfile originalProfile() { return originalProfile; }
  public GameProfile gameProfile() { return gameProfile; }
  /** Null puts the original back. */
  public void setGameProfile(GameProfile profile) { this.gameProfile = profile == null ? originalProfile : profile; }
}
