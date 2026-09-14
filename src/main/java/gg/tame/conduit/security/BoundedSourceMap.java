package gg.tame.conduit.security;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/** LRU-bounded map for hostile-source tracking. */
final class BoundedSourceMap<V> {
  private final int capacity;
  private final ReentrantLock lock = new ReentrantLock();
  private final LinkedHashMap<SourceKey, V> map;

  BoundedSourceMap(int capacity) {
    this.capacity = Math.max(16, capacity);
    this.map = new LinkedHashMap<>(64, 0.75f, true) {
      @Override protected boolean removeEldestEntry(Map.Entry<SourceKey, V> eldest) {
        return size() > BoundedSourceMap.this.capacity;
      }
    };
  }

  V compute(SourceKey key, Function<SourceKey, V> factory) {
    lock.lock();
    try {
      V value = map.get(key);
      if (value != null) return value;
      value = factory.apply(key);
      map.put(key, value);
      return value;
    } finally {
      lock.unlock();
    }
  }

  V get(SourceKey key) {
    lock.lock();
    try { return map.get(key); }
    finally { lock.unlock(); }
  }

  void put(SourceKey key, V value) {
    lock.lock();
    try { map.put(key, value); }
    finally { lock.unlock(); }
  }

  V remove(SourceKey key) {
    lock.lock();
    try { return map.remove(key); }
    finally { lock.unlock(); }
  }

  int size() {
    lock.lock();
    try { return map.size(); }
    finally { lock.unlock(); }
  }

  void forEachValue(java.util.function.Consumer<V> consumer) {
    lock.lock();
    try {
      for (V value : map.values()) consumer.accept(value);
    } finally {
      lock.unlock();
    }
  }
}
