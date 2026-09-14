package gg.tame.conduit.plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/** Live inventory of proxy plugins (native Conduit + Velocity-API compat). Backend server plugins are never listed. */
public final class PluginCatalog {
  public enum Kind { CONDUIT, VELOCITY }
  public record Entry(String id, String name, String version, Kind kind) {
    public String display() {
      String label = (name == null || name.isBlank()) ? id : name;
      String ver = (version == null || version.isBlank()) ? "" : " " + version;
      String tag = kind == Kind.VELOCITY ? "velocity" : "conduit";
      return label + ver + " [" + tag + "]";
    }
  }
  private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
  public void put(Entry entry) {
    if (entry == null || entry.id() == null || entry.id().isBlank() || entry.kind() == null) return;
    entries.put(entry.id().toLowerCase(Locale.ROOT), entry);
  }
  public void remove(String id) {
    if (id == null) return;
    entries.remove(id.toLowerCase(Locale.ROOT));
  }
  public Collection<Entry> all() {
    List<Entry> list = new ArrayList<>(entries.values());
    list.sort((a, b) -> {
      int byKind = Integer.compare(a.kind().ordinal(), b.kind().ordinal());
      if (byKind != 0) return byKind;
      return a.id().compareToIgnoreCase(b.id());
    });
    return List.copyOf(list);
  }
  public int size() { return entries.size(); }
}
