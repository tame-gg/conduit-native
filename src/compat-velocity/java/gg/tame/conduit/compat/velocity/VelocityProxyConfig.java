package gg.tame.conduit.compat.velocity;

import com.velocitypowered.api.proxy.config.ProxyConfig;
import com.velocitypowered.api.util.Favicon;
import gg.tame.conduit.runtime.ConduitRuntime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;

final class VelocityProxyConfig implements ProxyConfig {
  private final ConduitRuntime runtime;
  VelocityProxyConfig(ConduitRuntime runtime) { this.runtime = runtime; }
  @Override public boolean isQueryEnabled() { return false; }
  @Override public int getQueryPort() { return 0; }
  @Override public String getQueryMap() { return "Conduit"; }
  @Override public boolean shouldQueryShowPlugins() { return false; }
  @Override public Component getMotd() { return Component.text("Conduit"); }
  @Override public int getShowMaxPlayers() { return 1; }
  @Override public boolean isOnlineMode() { return true; }
  @Override public boolean shouldPreventClientProxyConnections() { return false; }
  @Override public Map<String, String> getServers() {
    Map<String, String> map = new HashMap<>();
    for (var server : runtime.servers().getServers()) map.put(server.getName(), server.getAddress().toString());
    return Map.copyOf(map);
  }
  @Override public List<String> getAttemptConnectionOrder() {
    return runtime.servers().getServers().stream().map(gg.tame.conduit.api.server.RegisteredServer::getName).toList();
  }
  @Override public Map<String, List<String>> getForcedHosts() { return Map.of(); }
  @Override public int getCompressionThreshold() { return -1; }
  @Override public int getCompressionLevel() { return -1; }
  @Override public int getLoginRatelimit() { return 0; }
  @Override public Optional<Favicon> getFavicon() { return Optional.empty(); }
  @Override public boolean isAnnounceForge() { return false; }
  @Override public int getConnectTimeout() { return 5000; }
  @Override public int getReadTimeout() { return 30000; }
  @Override public int getCommandRatelimit() { return 0; }
  @Override public boolean isForwardCommandsIfRateLimited() { return true; }
  @Override public int getKickAfterRateLimitedCommands() { return 0; }
  @Override public int getTabCompleteRatelimit() { return 0; }
  @Override public int getKickAfterRateLimitedTabCompletes() { return 0; }
}
