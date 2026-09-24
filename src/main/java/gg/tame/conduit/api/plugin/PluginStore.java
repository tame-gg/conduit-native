// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.plugin;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A small key-value store a plugin keeps between restarts, in {@code store.properties} under its
 * data directory. Strings in, strings out: enough for a setting, a counter, a last-seen time or a
 * JSON blob the plugin shapes itself, and the reason a plugin does not need to ship a database.
 *
 * <p>Every write goes to disk at once, through a temporary file and a rename, so a crash leaves the
 * previous contents rather than half of the new. Reads are from memory. One store per directory:
 * asking twice gives the same object.
 */
public final class PluginStore {
  private static final ConcurrentHashMap<Path, PluginStore> OPEN = new ConcurrentHashMap<>();
  private final Path file;
  private final Properties values = new Properties();

  private PluginStore(Path file) {
    this.file = file;
    if (Files.isRegularFile(file)) {
      try { values.load(new StringReader(Files.readString(file, StandardCharsets.UTF_8))); }
      catch (IOException unreadable) { throw new java.io.UncheckedIOException("cannot read " + file, unreadable); }
    }
  }

  /** The store in {@code dataDirectory}, opened on first use. */
  public static PluginStore in(Path dataDirectory) {
    return OPEN.computeIfAbsent(dataDirectory.toAbsolutePath().normalize().resolve("store.properties"), PluginStore::new);
  }

  public synchronized Optional<String> get(String key) { return Optional.ofNullable(values.getProperty(key)); }

  public synchronized String getOrDefault(String key, String fallback) { return values.getProperty(key, fallback); }

  /** Sets {@code key}, or removes it when {@code value} is null, and writes the file. */
  public synchronized void put(String key, String value) {
    if (key == null || key.isBlank()) throw new IllegalArgumentException("a key is needed");
    if (value == null) values.remove(key); else values.setProperty(key, value);
    save();
  }

  public synchronized void remove(String key) { put(key, null); }

  public synchronized Set<String> keys() { return new TreeSet<>(values.stringPropertyNames()); }

  private void save() {
    try {
      StringWriter out = new StringWriter();
      values.store(out, null);
      Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
      Files.createDirectories(file.getParent());
      Files.writeString(temporary, out.toString(), StandardCharsets.UTF_8);
      try {
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (java.nio.file.AtomicMoveNotSupportedException notAtomic) {
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException unwritable) {
      throw new java.io.UncheckedIOException("cannot write " + file, unwritable);
    }
  }
}
