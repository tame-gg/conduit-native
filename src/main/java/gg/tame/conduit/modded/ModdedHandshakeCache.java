package gg.tame.conduit.modded;

import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded, expiring cache of modded handshake classifications.
 * Keys are coarse source groups — never treated as permanent player identity.
 */
public final class ModdedHandshakeCache {
  public record CacheEntry(ModLoaderFamily family, FmlAddressMarkers.MarkerKind marker, long expiresAtNanos) {
    public boolean expired(long now) { return now >= expiresAtNanos; }
  }

  private final int capacity;
  private final long ttlNanos;
  private final ReentrantLock lock = new ReentrantLock();
  private final LinkedHashMap<String, CacheEntry> map;

  public ModdedHandshakeCache(int capacity, long ttlMillis) {
    this.capacity = Math.max(16, capacity);
    this.ttlNanos = Math.max(1_000L, ttlMillis) * 1_000_000L;
    this.map = new LinkedHashMap<>(64, 0.75f, true) {
      @Override protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
        return size() > ModdedHandshakeCache.this.capacity;
      }
    };
  }

  public void put(InetAddress address, int protocolVersion, ModLoaderFamily family, FmlAddressMarkers.MarkerKind marker) {
    if (address == null || family == null) return;
    String key = key(address, protocolVersion);
    long expires = System.nanoTime() + ttlNanos;
    lock.lock();
    try {
      evictExpiredLocked(System.nanoTime());
      map.put(key, new CacheEntry(family, marker == null ? FmlAddressMarkers.MarkerKind.NONE : marker, expires));
    } finally {
      lock.unlock();
    }
  }

  public Optional<CacheEntry> get(InetAddress address, int protocolVersion) {
    if (address == null) return Optional.empty();
    String key = key(address, protocolVersion);
    long now = System.nanoTime();
    lock.lock();
    try {
      CacheEntry entry = map.get(key);
      if (entry == null) return Optional.empty();
      if (entry.expired(now)) {
        map.remove(key);
        return Optional.empty();
      }
      return Optional.of(entry);
    } finally {
      lock.unlock();
    }
  }

  public boolean invalidate(InetAddress address) {
    if (address == null) return false;
    String prefix = address.getHostAddress() + "|";
    lock.lock();
    try {
      return map.entrySet().removeIf(entry -> entry.getKey().startsWith(prefix));
    } finally {
      lock.unlock();
    }
  }

  public void clear() {
    lock.lock();
    try { map.clear(); }
    finally { lock.unlock(); }
  }

  public int size() {
    lock.lock();
    try {
      evictExpiredLocked(System.nanoTime());
      return map.size();
    } finally {
      lock.unlock();
    }
  }

  private void evictExpiredLocked(long now) {
    map.entrySet().removeIf(entry -> entry.getValue().expired(now));
  }

  private static String key(InetAddress address, int protocolVersion) {
    return address.getHostAddress() + "|" + protocolVersion;
  }
}
