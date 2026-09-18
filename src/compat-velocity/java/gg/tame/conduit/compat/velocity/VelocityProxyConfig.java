package gg.tame.conduit.compat.velocity;

import com.velocitypowered.api.proxy.config.ProxyConfig;
import com.velocitypowered.api.util.Favicon;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;

/**
 * Conduit has no velocity.toml. The server list, online mode, connection order, forced hosts (it
 * has none) and server-list entry (MOTD, shown maximum, icon) are Conduit's real ones; every other
 * setting is Conduit's own business, not exposed by its API, and throws.
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

  @Override public boolean isQueryEnabled() { throw Unsupported.api("ProxyConfig.isQueryEnabled"); }
  @Override public int getQueryPort() { throw Unsupported.api("ProxyConfig.getQueryPort"); }
  @Override public String getQueryMap() { throw Unsupported.api("ProxyConfig.getQueryMap"); }
  @Override public boolean shouldQueryShowPlugins() { throw Unsupported.api("ProxyConfig.shouldQueryShowPlugins"); }
  /** As configured: what a given client sees can differ, after maintenance or a ProxyPingEvent listener. */
  @Override public Component getMotd() { return Texts.toAdventure(environment.conduit.serverListDefaults().description()); }
  @Override public int getShowMaxPlayers() { return environment.conduit.serverListDefaults().maxPlayers(); }
  @Override public boolean isOnlineMode() { return environment.conduit.onlineMode(); }
  @Override public boolean shouldPreventClientProxyConnections() { throw Unsupported.api("ProxyConfig.shouldPreventClientProxyConnections"); }
  @Override public List<String> getAttemptConnectionOrder() {
    return environment.conduit.servers().initialServers().stream().map(gg.tame.conduit.api.server.RegisteredServer::getName).toList();
  }
  /** Conduit routes by nothing but its initial and fallback lists: there are no forced hosts. */
  @Override public Map<String, List<String>> getForcedHosts() { return Map.of(); }
  @Override public int getCompressionThreshold() { throw Unsupported.api("ProxyConfig.getCompressionThreshold"); }
  @Override public int getCompressionLevel() { throw Unsupported.api("ProxyConfig.getCompressionLevel"); }
  @Override public int getLoginRatelimit() { throw Unsupported.api("ProxyConfig.getLoginRatelimit"); }
  @Override public Optional<Favicon> getFavicon() { return environment.conduit.serverListDefaults().favicon().map(Favicon::new); }
  @Override public boolean isAnnounceForge() { throw Unsupported.api("ProxyConfig.isAnnounceForge"); }
  @Override public int getConnectTimeout() { throw Unsupported.api("ProxyConfig.getConnectTimeout"); }
  @Override public int getReadTimeout() { throw Unsupported.api("ProxyConfig.getReadTimeout"); }
  @Override public int getCommandRatelimit() { throw Unsupported.api("ProxyConfig.getCommandRatelimit"); }
  @Override public boolean isForwardCommandsIfRateLimited() { throw Unsupported.api("ProxyConfig.isForwardCommandsIfRateLimited"); }
  @Override public int getKickAfterRateLimitedCommands() { throw Unsupported.api("ProxyConfig.getKickAfterRateLimitedCommands"); }
  @Override public int getTabCompleteRatelimit() { throw Unsupported.api("ProxyConfig.getTabCompleteRatelimit"); }
  @Override public int getKickAfterRateLimitedTabCompletes() { throw Unsupported.api("ProxyConfig.getKickAfterRateLimitedTabCompletes"); }
}
