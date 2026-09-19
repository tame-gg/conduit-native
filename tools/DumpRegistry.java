// SPDX-License-Identifier: GPL-3.0-or-later

import java.io.File;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Dumps a registry of an old vanilla server jar as {@code <id> <identifier>} lines.
 *
 * <p>The 1.13.x server's {@code --reports} predates the registry dump: it writes blocks.json,
 * items.json and commands.json and nothing else, so a 1.13 sound or particle id cannot be read out
 * of a report the way a 1.20.4 one can. The data is still in the jar, because the server builds the
 * same registries at boot. The source is Mojang's own jar, the same provenance the block and item
 * tables already have; nothing here comes from another proxy.
 *
 * <p>The jar is obfuscated, so nothing is looked up by name. The registry is found by what it
 * holds: every static field of every class is examined for an object that can list its keys and
 * whose keys include the probe identifier. <b>Ids are then asked of the registry</b>, through its
 * own by-id lookup, rather than inferred from declaration or iteration order — an order that
 * happened to be right would be indistinguishable from one that was not.
 *
 * <pre>
 *   javac -d out tools/DumpRegistry.java
 *   java -cp out DumpRegistry &lt;server.jar&gt; &lt;probe-identifier&gt; &lt;out.txt&gt;
 * </pre>
 *
 * The probe is any identifier the wanted registry certainly contains, for example
 * {@code minecraft:ambient.cave} for sounds or {@code minecraft:explosion} for particles.
 * Run it on a JDK the jar supports: JDK 8 for 1.13.
 *
 * <p>{@code tools/gen_sounds.py} and {@code tools/gen_particles.py} additionally check a dump
 * against the {@code registries.json} of a later version that still contains every one of these
 * identifiers, which catches a registry read wrongly as well as one read from the wrong field.
 */
public final class DumpRegistry {
  /** A registry smaller than this is something else that happens to hold identifiers. */
  private static final int MINIMUM_ENTRIES = 16;
  /** Packages that cannot hold a Minecraft registry; skipped so their classes are never loaded. */
  private static final String[] NOT_MINECRAFT =
      { "io.", "com.", "org.", "java", "it.", "oshi", "gnu.", "jline", "joptsimple" };

  private DumpRegistry() {}

  public static void main(String[] arguments) throws Exception {
    if (arguments.length != 3) {
      System.err.println("usage: DumpRegistry <server.jar> <probe-identifier> <out.txt>");
      System.exit(2);
    }
    File jar = new File(arguments[0]);
    String probe = arguments[1];
    File out = new File(arguments[2]);

    URLClassLoader loader = new URLClassLoader(new URL[] {jar.toURI().toURL()}, null);
    bootRegistries(loader);

    for (String className : classNames(jar)) {
      if (skip(className)) continue;
      Class<?> candidate;
      try {
        candidate = Class.forName(className, false, loader);
      } catch (Throwable notLoadable) {
        continue;
      }
      Field[] declared;
      try {
        declared = candidate.getDeclaredFields();
      } catch (Throwable unresolvable) {
        continue;
      }
      for (Field field : declared) {
        if (!Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
        Object value;
        try {
          field.setAccessible(true);
          value = field.get(null);
        } catch (Throwable inaccessible) {
          continue;
        }
        if (value == null || !holdsProbe(value, probe)) continue;
        List<String> identifiers = readByIds(value);
        if (identifiers == null) {
          System.err.println("found a registry holding " + probe + " at " + className + "."
              + field.getName() + " but could not read its ids");
          System.exit(1);
        }
        write(out, identifiers);
        System.out.println(className + "." + field.getName() + ": " + identifiers.size()
            + " entries -> " + out);
        return;
      }
    }
    System.err.println("no registry in " + jar + " contains " + probe);
    System.exit(1);
  }

  /**
   * Fills the registries.
   *
   * <p>They are filled by static initialisers, so something has to run them. The data generator's
   * entry point is the one class the jar does not obfuscate; running it boots the registries.
   */
  private static void bootRegistries(ClassLoader loader) throws Exception {
    File output = new File(System.getProperty("java.io.tmpdir"), "conduit-registry-dump");
    output.mkdirs();
    Class.forName("net.minecraft.data.Main", true, loader)
        .getMethod("main", String[].class)
        .invoke(null, (Object) new String[] {"--output", output.getAbsolutePath()});
  }

  /** Whether this object can list its keys and one of them is the probe. */
  private static boolean holdsProbe(Object candidate, String probe) {
    Set<String> keys = keys(candidate);
    return keys != null && keys.size() >= MINIMUM_ENTRIES && keys.contains(probe);
  }

  /** The identifiers a registry lists, or null when this object does not list any. */
  private static Set<String> keys(Object candidate) {
    for (Method method : candidate.getClass().getMethods()) {
      if (method.getParameterTypes().length != 0) continue;
      if (!Set.class.isAssignableFrom(method.getReturnType())
          && !Map.class.isAssignableFrom(method.getReturnType())) continue;
      Object result;
      try {
        method.setAccessible(true);
        result = method.invoke(candidate);
      } catch (Throwable notAccessor) {
        continue;
      }
      Iterable<?> entries = result instanceof Map ? ((Map<?, ?>) result).keySet()
          : result instanceof Set ? (Set<?>) result : null;
      if (entries == null) continue;
      Set<String> keys = new HashSet<String>();
      try {
        for (Object entry : entries) {
          String text = String.valueOf(entry);
          if (!looksLikeIdentifier(text)) return null;
          keys.add(text);
        }
      } catch (Throwable notIterable) {
        continue;
      }
      if (!keys.isEmpty()) return keys;
    }
    return null;
  }

  /**
   * The registry's entries, indexed by the registry's own ids.
   *
   * <p>Asked of the registry one id at a time rather than read off an iteration: a registry is free
   * to iterate in any order it likes, and one that happens to iterate in id order is
   * indistinguishable from one that does not until it is wrong on somebody's server. Null when the
   * object has no usable by-id lookup, when the ids are not a dense 0..n-1 range, or when an entry
   * does not resolve back to an identifier.
   */
  private static List<String> readByIds(Object registry) {
    int size = keys(registry).size();
    Method byId = null;
    for (Method method : registry.getClass().getMethods()) {
      Class<?>[] parameters = method.getParameterTypes();
      if (parameters.length != 1 || parameters[0] != int.class) continue;
      if (method.getReturnType() == void.class || method.getReturnType().isPrimitive()) continue;
      method.setAccessible(true);
      byId = method;
      break;
    }
    if (byId == null) return null;

    List<String> identifiers = new ArrayList<String>();
    Set<String> seen = new HashSet<String>();
    for (int id = 0; id < size; id++) {
      Object entry;
      try {
        entry = byId.invoke(registry, Integer.valueOf(id));
      } catch (Throwable failed) {
        return null;
      }
      if (entry == null) return null;                     // a hole: the ids are not 0..n-1
      String identifier = keyOf(registry, entry);
      if (identifier == null || !seen.add(identifier)) return null;
      identifiers.add(identifier);
    }
    return identifiers;
  }

  /** The identifier a registry gives one of its entries, asked of the registry. */
  private static String keyOf(Object registry, Object entry) {
    for (Method method : registry.getClass().getMethods()) {
      Class<?>[] parameters = method.getParameterTypes();
      if (parameters.length != 1 || !parameters[0].isInstance(entry)) continue;
      if (method.getReturnType().isPrimitive()) continue;
      Object result;
      try {
        method.setAccessible(true);
        result = method.invoke(registry, entry);
      } catch (Throwable notAccessor) {
        continue;
      }
      if (result == null) continue;
      String text = String.valueOf(result);
      if (looksLikeIdentifier(text)) return text;
    }
    return null;
  }

  private static boolean looksLikeIdentifier(String value) {
    int colon = value.indexOf(':');
    if (colon <= 0 || colon == value.length() - 1) return false;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
          || c == '_' || c == '.' || c == '-' || c == '/' || c == ':';
      if (!allowed) return false;
    }
    return true;
  }

  private static boolean skip(String className) {
    for (String prefix : NOT_MINECRAFT) {
      if (className.startsWith(prefix)) return true;
    }
    return false;
  }

  private static List<String> classNames(File jar) throws Exception {
    List<String> names = new ArrayList<String>();
    JarFile file = new JarFile(jar);
    try {
      Enumeration<JarEntry> entries = file.entries();
      while (entries.hasMoreElements()) {
        String name = entries.nextElement().getName();
        if (!name.endsWith(".class")) continue;
        names.add(name.substring(0, name.length() - ".class".length()).replace('/', '.'));
      }
    } finally {
      file.close();
    }
    return names;
  }

  private static void write(File out, List<String> identifiers) throws Exception {
    File parent = out.getParentFile();
    if (parent != null) parent.mkdirs();
    PrintWriter writer = new PrintWriter(out, "UTF-8");
    try {
      for (int id = 0; id < identifiers.size(); id++) {
        writer.println(id + " " + identifiers.get(id));
      }
    } finally {
      writer.close();
    }
  }
}
