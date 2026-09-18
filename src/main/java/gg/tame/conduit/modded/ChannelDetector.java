// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.modded;

import java.util.Locale;

/** Identifies mod-loader evidence from plugin-message channel identifiers. */
public final class ChannelDetector {
  private ChannelDetector() {}

  public enum ChannelClass {
    VANILLA,
    FABRIC,
    FORGE,
    NEOFORGE,
    OTHER,
    INVALID
  }

  public static ChannelClass classify(String channel) {
    if (channel == null || channel.isBlank()) return ChannelClass.INVALID;
    String normalized = channel.strip().toLowerCase(Locale.ROOT);
    if (normalized.length() > 256) return ChannelClass.INVALID;
    if (normalized.indexOf('\0') >= 0) return ChannelClass.INVALID;
    if (normalized.startsWith("neoforge:") || normalized.equals("neoforge:handshake")
        || normalized.startsWith("nf:") || normalized.contains(":neoforge")) {
      return ChannelClass.NEOFORGE;
    }
    if (normalized.startsWith("fml:") || normalized.startsWith("forge:")
        // 1.7-1.12 predates namespaced channels: FML1 talks on FML|HS, FML|MP and FORGE.
        || normalized.startsWith("fml|") || normalized.startsWith("forge|")
        || normalized.equals("fml") || normalized.equals("forge")) {
      return ChannelClass.FORGE;
    }
    if (normalized.startsWith("fabric:") || normalized.startsWith("quilt:")
        || normalized.equals("fabric:registry/sync") || normalized.startsWith("fabric-")) {
      return ChannelClass.FABRIC;
    }
    if (normalized.equals("minecraft:brand") || normalized.equals("mc|brand")
        || normalized.startsWith("minecraft:") || normalized.startsWith("bungeecord:")
        || normalized.startsWith("velocity:")) {
      return ChannelClass.VANILLA;
    }
    return ChannelClass.OTHER;
  }

  public static ModLoaderFamily familyHint(ChannelClass channelClass) {
    return switch (channelClass) {
      case FABRIC -> ModLoaderFamily.FABRIC;
      case FORGE -> ModLoaderFamily.FORGE;
      case NEOFORGE -> ModLoaderFamily.NEOFORGE;
      case VANILLA -> ModLoaderFamily.VANILLA;
      case OTHER, INVALID -> ModLoaderFamily.UNKNOWN;
    };
  }
}
