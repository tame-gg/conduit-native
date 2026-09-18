// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event.proxy;

import gg.tame.conduit.api.event.Cancellable;
import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.text.Text;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A client is asking for the server-list entry: its MOTD, player counts, version and icon.
 *
 * <p>Every field starts as the proxy's own answer -- the configured {@code [status]} section, the
 * maintenance MOTD while maintenance is on, the version gate's name and protocol for a client it
 * turns away, and the real online count with a sample of up to 12 players -- and whatever the
 * listeners leave in it is what the client is sent. Cancelling closes the connection with no answer,
 * so the client shows the server as unreachable. Fired once per status request, on the
 * connection's own thread, which holds the answer until every listener returns: do not block.
 *
 * <p>{@link #maxPlayers} is only the number the list shows; it limits nothing. The setters refuse
 * null, so a listener that passes one fails without taking the answer with it.
 */
public final class ServerListPingEvent implements Event, Cancellable {
  /** One line of the hover list of players. */
  public record SamplePlayer(String name, UUID uniqueId) {
    public SamplePlayer {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(uniqueId, "uniqueId");
    }
  }

  private final InetSocketAddress remoteAddress;
  private final Optional<String> virtualHost;
  private final int virtualPort;
  private final int protocolVersion;
  private volatile Text description;
  private volatile int maxPlayers;
  private volatile int onlinePlayers;
  private volatile List<SamplePlayer> samplePlayers;
  private volatile String versionName;
  private volatile int versionProtocol;
  private volatile Optional<String> favicon;
  private volatile boolean playersHidden;
  private volatile boolean cancelled;

  public ServerListPingEvent(InetSocketAddress remoteAddress, Optional<String> virtualHost, int virtualPort, int protocolVersion,
                             Text description, int maxPlayers, int onlinePlayers, List<SamplePlayer> samplePlayers,
                             String versionName, int versionProtocol, Optional<String> favicon) {
    this.remoteAddress = remoteAddress; this.virtualHost = virtualHost; this.virtualPort = virtualPort;
    this.protocolVersion = protocolVersion;
    setDescription(description); setMaxPlayers(maxPlayers); setOnlinePlayers(onlinePlayers); setSamplePlayers(samplePlayers);
    setVersionName(versionName); setVersionProtocol(versionProtocol); setFavicon(favicon);
  }

  /** The socket peer that asked. */
  public InetSocketAddress remoteAddress() { return remoteAddress; }
  /** The host the client says it dialled, from its handshake, with Forge markers removed. */
  public Optional<String> virtualHost() { return virtualHost; }
  /** The port the client says it dialled, from its handshake. */
  public int virtualPort() { return virtualPort; }
  /** The client's own protocol number, which may be one the proxy cannot serve. */
  public int protocolVersion() { return protocolVersion; }

  public Text description() { return description; }
  public void setDescription(Text description) { this.description = Objects.requireNonNull(description, "description"); }
  public int maxPlayers() { return maxPlayers; }
  public void setMaxPlayers(int maxPlayers) { this.maxPlayers = maxPlayers; }
  public int onlinePlayers() { return onlinePlayers; }
  public void setOnlinePlayers(int onlinePlayers) { this.onlinePlayers = onlinePlayers; }
  /** Shown when the pointer is over the player count. The client shows at most about a dozen. */
  public List<SamplePlayer> samplePlayers() { return samplePlayers; }
  public void setSamplePlayers(List<SamplePlayer> samplePlayers) { this.samplePlayers = List.copyOf(samplePlayers); }
  public String versionName() { return versionName; }
  public void setVersionName(String versionName) { this.versionName = Objects.requireNonNull(versionName, "versionName"); }
  /**
   * The protocol the answer claims. The client marks the entry incompatible and shows
   * {@link #versionName} instead of the player count when it differs from its own.
   */
  public int versionProtocol() { return versionProtocol; }
  public void setVersionProtocol(int versionProtocol) { this.versionProtocol = versionProtocol; }
  /** A {@code data:image/png;base64,} URI of a 64x64 PNG, or empty for no icon. */
  public Optional<String> favicon() { return favicon; }
  public void setFavicon(Optional<String> favicon) { this.favicon = Objects.requireNonNull(favicon, "favicon"); }
  /**
   * Whether the answer leaves out the player counts and sample altogether, which the client shows
   * as "???". The counts and sample set here are kept, and sent again if this is turned back off.
   */
  public boolean playersHidden() { return playersHidden; }
  public void setPlayersHidden(boolean playersHidden) { this.playersHidden = playersHidden; }

  @Override public boolean cancelled() { return cancelled; }
  @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
}
