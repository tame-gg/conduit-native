// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/** Live inventory of proxy plugins, native and every other loaded format. Backend server plugins are never listed. */
public final class PluginCatalog {
  public static final String NATIVE = "conduit";
  public enum Kind {
    CONDUIT(NATIVE), VELOCITY("velocity");
    private final String format;
    Kind(String format) { this.format = format; }
    public String format() { return format; }
  }
  /** {@code format} is the tag /plugins shows: "conduit" for native jars, a loader's own name otherwise. */
  public record Entry(String id, String name, String version, String format) {
    public Entry(String id, String name, String version, Kind kind) { this(id, name, version, kind == null ? null : kind.format()); }
    public String display() {
      String label = (name == null || name.isBlank()) ? id : name;
      String ver = (version == null || version.isBlank()) ? "" : " " + version;
      return label + ver + " [" + format + "]";
    }
  }
  private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
  public void put(Entry entry) {
    if (entry == null || entry.id() == null || entry.id().isBlank() || entry.format() == null || entry.format().isBlank()) return;
    entries.put(entry.id().toLowerCase(Locale.ROOT), entry);
  }
  public void remove(String id) {
    if (id == null) return;
    entries.remove(id.toLowerCase(Locale.ROOT));
  }
  public Collection<Entry> all() {
    List<Entry> list = new ArrayList<>(entries.values());
    // Native first, then each other format together.
    list.sort(Comparator.comparing((Entry entry) -> !entry.format().equals(NATIVE))
        .thenComparing(Entry::format)
        .thenComparing(Entry::id, String.CASE_INSENSITIVE_ORDER));
    return List.copyOf(list);
  }
  public int size() { return entries.size(); }
}
