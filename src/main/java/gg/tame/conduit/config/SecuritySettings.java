// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.config;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Application-level abuse mitigation. Not network/DDoS protection. */
public record SecuritySettings(
    ThrottleSettings throttle,
    BotFilterSettings botFilter,
    ChannelGuardSettings channelGuard,
    AttackModeSettings attackMode
) {
  public SecuritySettings {
    if (throttle == null) throttle = ThrottleSettings.defaults();
    if (botFilter == null) botFilter = BotFilterSettings.defaults();
    if (channelGuard == null) channelGuard = ChannelGuardSettings.defaults();
    if (attackMode == null) attackMode = AttackModeSettings.defaults();
  }

  public static SecuritySettings defaults() {
    return new SecuritySettings(
        ThrottleSettings.defaults(),
        BotFilterSettings.defaults(),
        ChannelGuardSettings.defaults(),
        AttackModeSettings.defaults());
  }

  public record ThrottleSettings(
      boolean enabled,
      int maxAttempts,
      int windowMs,
      int maxConcurrent,
      int ipv4Prefix,
      int ipv6Prefix,
      long logIntervalMs
  ) {
    public ThrottleSettings {
      if (maxAttempts < 1) throw new IllegalArgumentException("security.throttle.max-attempts must be >= 1");
      if (windowMs < 100) throw new IllegalArgumentException("security.throttle.window-ms must be >= 100");
      if (maxConcurrent < 1) throw new IllegalArgumentException("security.throttle.max-concurrent must be >= 1");
      if (ipv4Prefix < 8 || ipv4Prefix > 32) throw new IllegalArgumentException("security.throttle.ipv4-prefix must be 8..32");
      if (ipv6Prefix < 16 || ipv6Prefix > 128) throw new IllegalArgumentException("security.throttle.ipv6-prefix must be 16..128");
      if (logIntervalMs < 500) throw new IllegalArgumentException("security.throttle.log-interval-ms must be >= 500");
    }
    public static ThrottleSettings defaults() {
      // NAT-friendly: many clients can share one public IPv4.
      return new ThrottleSettings(true, 40, 1_000, 32, 32, 64, 5_000);
    }
  }

  public record BotFilterSettings(
      boolean enabled,
      int strikeThreshold,
      int handshakeTimeoutMs,
      int blockDurationMs,
      int strikeWindowMs
  ) {
    public BotFilterSettings {
      if (strikeThreshold < 1) throw new IllegalArgumentException("security.bot-filter.strike-threshold must be >= 1");
      if (handshakeTimeoutMs < 200) throw new IllegalArgumentException("security.bot-filter.handshake-timeout-ms must be >= 200");
      if (blockDurationMs < 1_000) throw new IllegalArgumentException("security.bot-filter.block-duration-ms must be >= 1000");
      if (strikeWindowMs < 1_000) throw new IllegalArgumentException("security.bot-filter.strike-window-ms must be >= 1000");
    }
    public static BotFilterSettings defaults() {
      return new BotFilterSettings(true, 10, 3_000, 60_000, 60_000);
    }
  }

  public enum ChannelAction {
    OFF, LOG, DROP, KICK;
    public static ChannelAction parse(String raw) {
      try { return ChannelAction.valueOf(raw.strip().toUpperCase(Locale.ROOT)); }
      catch (RuntimeException exception) { throw new IllegalArgumentException("security.channel-guard.default-action must be off, log, drop or kick"); }
    }
  }

  public record ChannelGuardSettings(
      boolean enabled,
      ChannelAction defaultAction,
      Map<String, ChannelAction> channels
  ) {
    public ChannelGuardSettings {
      if (defaultAction == null) defaultAction = ChannelAction.LOG;
      Map<String, ChannelAction> copy = new LinkedHashMap<>();
      if (channels != null) {
        for (Map.Entry<String, ChannelAction> entry : channels.entrySet()) {
          copy.put(normalizeChannel(entry.getKey()), entry.getValue() == null ? ChannelAction.LOG : entry.getValue());
        }
      }
      channels = Map.copyOf(copy);
    }
    public static ChannelGuardSettings defaults() {
      // Documented example rules; disabled by default so unknown channels stay allowed.
      Map<String, ChannelAction> examples = new LinkedHashMap<>();
      examples.put("wdl:init", ChannelAction.DROP);
      examples.put("wdl:control", ChannelAction.DROP);
      examples.put("schematica", ChannelAction.LOG);
      return new ChannelGuardSettings(false, ChannelAction.LOG, examples);
    }
    public static String normalizeChannel(String channel) {
      if (channel == null) return "";
      String value = channel.strip().toLowerCase(Locale.ROOT);
      if (value.startsWith("minecraft:brand")) return "minecraft:brand";
      return value;
    }
  }

  public record AttackModeSettings(
      int throttleMaxAttempts,
      int botStrikeThreshold
  ) {
    public AttackModeSettings {
      if (throttleMaxAttempts < 1) throw new IllegalArgumentException("security.attack-mode.throttle-max-attempts must be >= 1");
      if (botStrikeThreshold < 1) throw new IllegalArgumentException("security.attack-mode.bot-strike-threshold must be >= 1");
    }
    public static AttackModeSettings defaults() {
      return new AttackModeSettings(8, 3);
    }
  }
}
