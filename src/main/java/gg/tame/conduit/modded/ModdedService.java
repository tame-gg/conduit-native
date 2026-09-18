// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.modded;

import gg.tame.conduit.config.ModdedSettings;
import gg.tame.conduit.log.ConduitLog;
import java.net.InetAddress;

/** Facade for Phase 3 modded protocol subsystems. */
public final class ModdedService {
  private volatile ModdedSettings settings;
  private volatile ModdedHandshakeCache cache;

  public ModdedService(ModdedSettings settings) {
    applySettings(settings);
  }

  public synchronized void applySettings(ModdedSettings settings) {
    this.settings = settings;
    this.cache = new ModdedHandshakeCache(settings.handshakeCacheCapacity(), settings.handshakeCacheTtlMs());
  }

  public ModdedSettings settings() { return settings; }
  public ModdedHandshakeCache cache() { return cache; }

  public HandshakeClassifier classifyHandshake(String requestedHost, InetAddress address, int protocolVersion) {
    HandshakeClassifier classifier = new HandshakeClassifier();
    try {
      classifier.observeHandshakeHost(requestedHost);
    } catch (IllegalArgumentException exception) {
      throw exception;
    }
    if (settings.handshakeCacheEnabled() && address != null) {
      cache.get(address, protocolVersion).ifPresent(entry -> classifier.restore(entry.family(), entry.marker()));
    }
    return classifier;
  }

  public void remember(InetAddress address, int protocolVersion, HandshakeClassifier classifier) {
    if (!settings.handshakeCacheEnabled() || address == null || classifier == null) return;
    if (classifier.family() == ModLoaderFamily.UNKNOWN) return;
    cache.put(address, protocolVersion, classifier.family(), classifier.marker());
    if (settings.logModHandshakes()) {
      ConduitLog.info("Mod handshake classified as " + classifier.family().displayName());
    }
  }

  public SwitchPacketQueue newSwitchQueue() {
    return new SwitchPacketQueue(settings.packetQueueMaxDepth());
  }

  public int knownPacksLimit() { return settings.knownPacksLimit(); }

  public boolean invalidateCache(InetAddress address) {
    return cache.invalidate(address);
  }
}
