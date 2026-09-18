package gg.tame.conduit.compat.velocity;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * One Velocity plugin jar. Delegates to the proxy's class path with Conduit's own packages cut out,
 * so a Velocity plugin links against the Velocity API and its libraries and nothing of Conduit's.
 * After its own jar it looks in the other Velocity plugins' jars, which is how a plugin uses the API
 * of a plugin it depends on.
 */
final class VelocityClassLoader extends URLClassLoader {
  static { registerAsParallelCapable(); }

  private static final String HIDDEN_PACKAGE = "gg.tame.conduit.";
  private static final String HIDDEN_RESOURCES = "gg/tame/conduit/";
  /** The proxy class path minus Conduit. */
  private static final ClassLoader VISIBLE = new WithoutConduit(VelocityClassLoader.class.getClassLoader());
  private static final class WithoutConduit extends ClassLoader {
    static { registerAsParallelCapable(); }
    WithoutConduit(ClassLoader parent) { super(parent); }
    @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (name.startsWith(HIDDEN_PACKAGE)) throw new ClassNotFoundException(name + " is not visible to Velocity plugins");
      return super.loadClass(name, resolve);
    }
    @Override public URL getResource(String name) { return name.startsWith(HIDDEN_RESOURCES) ? null : super.getResource(name); }
    @Override public Enumeration<URL> getResources(String name) throws IOException {
      return name.startsWith(HIDDEN_RESOURCES) ? Collections.emptyEnumeration() : super.getResources(name);
    }
  }

  private final Set<VelocityClassLoader> siblings;
  VelocityClassLoader(URL jar, Set<VelocityClassLoader> siblings) {
    super(new URL[] { jar }, VISIBLE);
    this.siblings = siblings;
    siblings.add(this);
  }
  static Set<VelocityClassLoader> newRegistry() { return new CopyOnWriteArraySet<>(); }

  @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    try {
      return super.loadClass(name, resolve);
    } catch (ClassNotFoundException notHere) {
      if (name.startsWith(HIDDEN_PACKAGE)) throw notHere;
      for (VelocityClassLoader sibling : siblings) {
        if (sibling == this) continue;
        Class<?> found = sibling.ownClass(name);
        if (found != null) return found;
      }
      throw notHere;
    }
  }
  /** This jar only: never the siblings, so two loaders can never chase each other. */
  private Class<?> ownClass(String name) {
    synchronized (getClassLoadingLock(name)) {
      Class<?> loaded = findLoadedClass(name);
      if (loaded != null) return loaded;
      try { return findClass(name); } catch (ClassNotFoundException | IllegalStateException absent) { return null; }
    }
  }
  /** PluginManager.addToClasspath. */
  @Override public void addURL(URL url) { super.addURL(url); }
  @Override public void close() throws IOException {
    siblings.remove(this);
    super.close();
  }
}
