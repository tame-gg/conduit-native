package gg.tame.conduit.plugin;

import java.nio.file.Path;
import java.util.jar.JarFile;

/** Optional extra plugin format (Velocity JARs, etc.). Core does not import Velocity types. */
public interface ExternalJarHandler {
  boolean accepts(JarFile jar);
  void load(Path jar) throws Exception;
  default void shutdown() {}
}
