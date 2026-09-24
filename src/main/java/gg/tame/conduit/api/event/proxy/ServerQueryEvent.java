// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event.proxy;

import gg.tame.conduit.api.event.Event;
import java.net.InetAddress;
import java.util.List;

/**
 * A GameSpy 4 query ({@code [query]} in conduit.toml) is about to be answered: {@code full} for a
 * full stat, which lists players and plugins, false for a basic one. A listener may replace the
 * {@link Response}. Fired on the query thread, which answers one query at a time: do not block.
 */
public final class ServerQueryEvent implements Event {
  private final boolean full;
  private final InetAddress querier;
  private volatile Response response;

  public ServerQueryEvent(boolean full, InetAddress querier, Response response) {
    this.full = full;
    this.querier = querier;
    setResponse(response);
  }
  public boolean full() { return full; }
  public InetAddress querier() { return querier; }
  public Response response() { return response; }
  public void setResponse(Response response) {
    if (response == null) throw new IllegalArgumentException("a query response is required");
    this.response = response;
  }

  /** What the query answers; {@code motd} is plain text. */
  public record Response(String motd, String gameVersion, String map, int onlinePlayers, int maxPlayers, String host, int port,
                         List<String> players, String proxyVersion, List<PluginInfo> plugins) {
    public Response {
      players = List.copyOf(players);
      plugins = List.copyOf(plugins);
    }
  }
  /** A plugin as a full stat lists it; {@code version} may be null. */
  public record PluginInfo(String name, String version) {}
}
