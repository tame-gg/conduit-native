package gg.tame.conduit.compat.velocity;

import com.velocitypowered.api.proxy.config.ProxyConfig;
import com.velocitypowered.api.util.Favicon;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;

/**
 * Conduit has no velocity.toml. The server list, online mode and connection order are Conduit's
 * real ones; every other setting is Conduit's own business, not exposed by its API, and throws.
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
  @Override public Component getMotd() { throw Unsupported.api("ProxyConfig.getMotd"); }
  @Override public int getShowMaxPlayers() { throw Unsupported.api("ProxyConfig.getShowMaxPlayers"); }
  @Override public boolean isOnlineMode() { return environment.conduit.onlineMode(); }
  @Override public boolean shouldPreventClientProxyConnections() { throw Unsupported.api("ProxyConfig.shouldPreventClientProxyConnections"); }
  @Override public List<String> getAttemptConnectionOrder() {
    return environment.conduit.servers().initialServers().stream().map(gg.tame.conduit.api.server.RegisteredServer::getName).toList();
  }
  @Override public Map<String, List<String>> getForcedHosts() { throw Unsupported.api("ProxyConfig.getForcedHosts"); }
  @Override public int getCompressionThreshold() { throw Unsupported.api("ProxyConfig.getCompressionThreshold"); }
  @Override public int getCompressionLevel() { throw Unsupported.api("ProxyConfig.getCompressionLevel"); }
  @Override public int getLoginRatelimit() { throw Unsupported.api("ProxyConfig.getLoginRatelimit"); }
  @Override public Optional<Favicon> getFavicon() { throw Unsupported.api("ProxyConfig.getFavicon"); }
  @Override public boolean isAnnounceForge() { throw Unsupported.api("ProxyConfig.isAnnounceForge"); }
  @Override public int getConnectTimeout() { throw Unsupported.api("ProxyConfig.getConnectTimeout"); }
  @Override public int getReadTimeout() { throw Unsupported.api("ProxyConfig.getReadTimeout"); }
  @Override public int getCommandRatelimit() { throw Unsupported.api("ProxyConfig.getCommandRatelimit"); }
  @Override public boolean isForwardCommandsIfRateLimited() { throw Unsupported.api("ProxyConfig.isForwardCommandsIfRateLimited"); }
  @Override public int getKickAfterRateLimitedCommands() { throw Unsupported.api("ProxyConfig.getKickAfterRateLimitedCommands"); }
  @Override public int getTabCompleteRatelimit() { throw Unsupported.api("ProxyConfig.getTabCompleteRatelimit"); }
  @Override public int getKickAfterRateLimitedTabCompletes() { throw Unsupported.api("ProxyConfig.getKickAfterRateLimitedTabCompletes"); }
}
