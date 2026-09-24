// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.compat.velocity;

import com.velocitypowered.api.proxy.config.ProxyConfig;
import com.velocitypowered.api.util.Favicon;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;

/**
 * Conduit has no velocity.toml. The server list, online mode, connection order, compression and
 * server-list entry (MOTD, shown maximum, icon) are Conduit's real ones. A feature Conduit does not
 * have answers as Velocity does with it turned off -- no rate limits, no Forge
 * announcement -- rather than throwing: plugins read this to describe the proxy, and one that logs
 * its whole configuration at start was stopped by the first setting it asked about.
 */
final class VelocityProxyConfig implements ProxyConfig {
  private final VelocityEnvironment environment;
  VelocityProxyConfig(VelocityEnvironment environment) { this.environment = environment; }

  @Override public Map<String, String> getServers() {
    Map<String, String> servers = new LinkedHashMap<>();
    for (var server : environment.conduit.servers().getServers()) {
      servers.put(server.getName(), server.getAddress().getHostString() + ":" + server.getAddress().getPort());
    }
    return Map.copyOf(servers);
  }

  /** Whether Conduit's GameSpy 4 query ({@code [query]}) is answering, and on which UDP port (0 when it is not). */
  @Override public boolean isQueryEnabled() { return environment.conduit.queryPort().isPresent(); }
  @Override public int getQueryPort() { return environment.conduit.queryPort().orElse(0); }
  @Override public String getQueryMap() { return "Conduit"; }
  @Override public boolean shouldQueryShowPlugins() { return false; }
  /** As configured: what a given client sees can differ, after maintenance or a ProxyPingEvent listener. */
  @Override public Component getMotd() { return Texts.toAdventure(environment.conduit.serverListDefaults().description()); }
  @Override public int getShowMaxPlayers() { return environment.conduit.serverListDefaults().maxPlayers(); }
  @Override public boolean isOnlineMode() { return environment.conduit.onlineMode(); }
  /** Conduit never sends the session server a player's address to check. */
  @Override public boolean shouldPreventClientProxyConnections() { return false; }
  @Override public List<String> getAttemptConnectionOrder() {
    return environment.conduit.servers().initialServers().stream().map(gg.tame.conduit.api.server.RegisteredServer::getName).toList();
  }
  /** {@code [forced-hosts]}, each host as Conduit matches it: lower-cased, without a trailing dot. */
  @Override public Map<String, List<String>> getForcedHosts() { return environment.conduit.forcedHosts(); }
  @Override public int getCompressionThreshold() { return environment.conduit.compressionThreshold(); }
  /** The deflate level Conduit compresses at, which is not configurable. */
  @Override public int getCompressionLevel() { return 4; }
  /** No delay between one address's logins: Conduit's throttle counts attempts in a window instead. */
  @Override public int getLoginRatelimit() { return 0; }
  @Override public Optional<Favicon> getFavicon() { return environment.conduit.serverListDefaults().favicon().map(Favicon::new); }
  @Override public boolean isAnnounceForge() { return false; }
  /** How long Conduit waits for a backend to accept a connection. */
  @Override public int getConnectTimeout() { return 3_000; }
  /** How long a backend may go quiet while logging a player in; a playing connection has no read timeout. */
  @Override public int getReadTimeout() { return 4_000; }
  /** Conduit rate-limits neither commands nor completions, so nothing is withheld or kicked over them. */
  @Override public int getCommandRatelimit() { return 0; }
  @Override public boolean isForwardCommandsIfRateLimited() { return true; }
  @Override public int getKickAfterRateLimitedCommands() { return 0; }
  @Override public int getTabCompleteRatelimit() { return 0; }
  @Override public int getKickAfterRateLimitedTabCompletes() { return 0; }
}
