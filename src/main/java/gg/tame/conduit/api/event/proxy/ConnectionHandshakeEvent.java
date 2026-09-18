// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event.proxy;

import gg.tame.conduit.api.event.Event;
import java.net.InetSocketAddress;

/**
 * A client connected and sent a well-formed handshake, for a server-list ping, a login, or (1.20.5+)
 * a login after a transfer. Nothing about it has been answered or checked yet beyond the handshake
 * itself: not the protocol, not the version gate. {@code remoteAddress} is the socket's peer,
 * {@code virtualHost} the host and port the client says it dialled, unresolved, with Forge markers
 * removed. Fired on the connection's own thread, only when something listens: do not block, since a
 * listener delays every ping and every login.
 */
public record ConnectionHandshakeEvent(InetSocketAddress remoteAddress, InetSocketAddress virtualHost, int protocolVersion,
                                       Intent intent) implements Event {
  /** What the client asked the handshake for. */
  public enum Intent { STATUS, LOGIN, TRANSFER }
}
