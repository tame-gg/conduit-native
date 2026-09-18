// SPDX-License-Identifier: GPL-3.0-or-later

import java.io.File;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
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
 * <p>The jar is obfuscated, so nothing is looked up by name. What is looked for is the class that
 * declares the registry's entries as static fields — {@code SoundEvents} holds one static field per
 * sound, {@code Particles} one per particle type — found by scanning for the class whose static
 * fields yield the probe identifier. Those fields are declared in registration order, so their
 * order is the registry's order, and the id of an entry is its position.
 *
 * <p><b>That last step is an inference, so it is checked rather than trusted.</b>
 * {@code tools/gen_sound_particle.py} verifies the dump against the {@code registries.json} of a
 * later version that still contains every one of these identifiers: if the order here is the real
 * registration order, the same names appear in the same relative order there. For 1.13 sounds, all
 * 662 names appear in 1.14's registry in exactly this order. A dump that fails that check must not
 * be used.
 *
 * <pre>
 *   javac -d out tools/DumpRegistry.java
 *   java -cp out DumpRegistry &lt;server.jar&gt; &lt;probe-identifier&gt; &lt;out.txt&gt;
 * </pre>
 *
 * The probe is any identifier the wanted registry certainly contains, for example
 * {@code minecraft:ambient.cave} for sounds or {@code minecraft:explosion} for particles.
 * Run it on a JDK the jar supports: JDK 8 for 1.13.
 */
public final class DumpRegistry {
  /** Fewer static fields than this and the class cannot be a registry holder worth dumping. */
  private static final int MINIMUM_ENTRIES = 16;

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
      Class<?> candidate;
      try {
        candidate = Class.forName(className, false, loader);
      } catch (Throwable notLoadable) {
        continue;
      }
      List<String> identifiers = identifiersOf(candidate);
      if (identifiers.size() >= MINIMUM_ENTRIES && identifiers.contains(probe)) {
        write(out, identifiers);
        System.out.println(className + ": " + identifiers.size() + " entries -> " + out);
        return;
      }
    }
    System.err.println("no class in " + jar + " declares " + probe);
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

  /**
   * The identifier of every static field of this class, in declaration order.
   *
   * <p>A registry entry carries its own identifier in an instance field — a sound event holds the
   * name it was registered under. The field is found by what it holds rather than by its name,
   * which is obfuscated. Only classes whose static fields all share one type are considered, so a
   * class that merely happens to hold a few identifiers is not mistaken for a registry.
   */
  private static List<String> identifiersOf(Class<?> type) {
    Field[] declared;
    try {
      declared = type.getDeclaredFields();
    } catch (Throwable unresolvable) {
      return new ArrayList<String>();
    }
    Map<Class<?>, Integer> byFieldType = new HashMap<Class<?>, Integer>();
    for (Field field : declared) {
      if (!Modifier.isStatic(field.getModifiers())) continue;
      Integer count = byFieldType.get(field.getType());
      byFieldType.put(field.getType(), count == null ? 1 : count + 1);
    }
    Class<?> entryType = null;
    int best = 0;
    for (Map.Entry<Class<?>, Integer> entry : byFieldType.entrySet()) {
      if (entry.getValue() > best && !entry.getKey().isPrimitive()) {
        best = entry.getValue();
        entryType = entry.getKey();
      }
    }
    List<String> identifiers = new ArrayList<String>();
    // Reading a static field initialises its class, which for a jar this size is most of the game.
    // A registry holder declares one field per entry, so a class with few is not one: rule it out
    // on the field count alone, before anything is read.
    if (entryType == null || best < MINIMUM_ENTRIES) return identifiers;
    for (Field field : declared) {
      if (!Modifier.isStatic(field.getModifiers()) || field.getType() != entryType) continue;
      Object value;
      try {
        field.setAccessible(true);
        value = field.get(null);
      } catch (Throwable inaccessible) {
        return new ArrayList<String>();
      }
      String identifier = identifierOf(value);
      if (identifier == null) return new ArrayList<String>();  // not every entry names itself
      identifiers.add(identifier);
    }
    return identifiers;
  }

  /** The one identifier-shaped value held by this object, or null when it holds none. */
  private static String identifierOf(Object entry) {
    if (entry == null) return null;
    for (Field field : entry.getClass().getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers())) continue;
      Object value;
      try {
        field.setAccessible(true);
        value = field.get(entry);
      } catch (Throwable inaccessible) {
        continue;
      }
      if (value == null) continue;
      String text = String.valueOf(value);
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

  private static List<String> classNames(File jar) throws Exception {
    List<String> names = new ArrayList<String>();
    JarFile file = new JarFile(jar);
    try {
      Enumeration<JarEntry> entries = file.entries();
      while (entries.hasMoreElements()) {
        String name = entries.nextElement().getName();
        if (!name.endsWith(".class") || name.contains("$")) continue;
        names.add(name.substring(0, name.length() - ".class".length()).replace('/', '.'));
      }
    } finally {
      file.close();
    }
    return names;
  }

  private static void write(File out, List<String> identifiers) throws Exception {
    Set<String> seen = new LinkedHashSet<String>(identifiers);
    if (seen.size() != identifiers.size()) {
      throw new IllegalStateException("the same identifier is declared twice: not a registry");
    }
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
