// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.boot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The ViaVersion artifacts Conduit needs, which versions of them are inside the jar, and which are on
 * disk in {@code lib/via}.
 *
 * <p>Nothing in {@code gg.tame.conduit.boot} may touch a Conduit or Via class. It runs before the
 * class loader that will hold those is built, so loading one here would load it in the wrong loader
 * and leave two copies of it in the process. Plain JDK only, and values that cross back out are
 * strings and paths.
 */
public final class ViaArtifacts {
  /**
   * The five artifacts, and where each lives.
   *
   * <p>Version lines differ: ViaVersion, ViaBackwards and ViaRewind track Minecraft, ViaLegacy does
   * not, so each is resolved on its own rather than assuming one shared version.
   */
  public static final Map<String, String> GROUPS = Map.of(
      "viaversion-api", "com/viaversion",
      "viaversion-common", "com/viaversion",
      "viabackwards-common", "com/viaversion",
      "viarewind-common", "com/viaversion",
      "ViaLegacy", "net/raphimc");

  /** In the order they go on a class path; the api before the common that implements it. */
  public static final List<String> NAMES = List.of(
      "viaversion-api", "viaversion-common", "viabackwards-common", "viarewind-common", "ViaLegacy");

  private static final String BUNDLED_RESOURCE = "/gg/tame/conduit/via-bundled.properties";

  private ViaArtifacts() {}

  /** Artifact to version, for the copies merged into the jar. */
  public static Map<String, String> bundled() throws IOException {
    Properties properties = new Properties();
    try (InputStream stream = ViaArtifacts.class.getResourceAsStream(BUNDLED_RESOURCE)) {
      if (stream == null) throw new IOException("this build is missing " + BUNDLED_RESOURCE);
      properties.load(stream);
    }
    Map<String, String> versions = new LinkedHashMap<>();
    for (String name : NAMES) {
      String version = properties.getProperty(name);
      if (version == null || version.isBlank()) throw new IOException(BUNDLED_RESOURCE + " does not name " + name);
      versions.put(name, version.strip());
    }
    return versions;
  }

  /** A jar of one artifact found in a directory: its version and its file. */
  public record Found(String version, Path file) {}

  /**
   * The newest jar of each artifact directly in {@code directory}, ignoring subdirectories -- which is
   * how {@code superseded/} keeps an old set out of the way without keeping it off the class path.
   *
   * <p>A directory with two versions of one artifact would otherwise put both on the class path, and
   * which one won would come down to file order.
   */
  public static Map<String, Found> inDirectory(Path directory) throws IOException {
    Map<String, Found> found = new LinkedHashMap<>();
    if (!Files.isDirectory(directory)) return found;
    try (var entries = Files.list(directory)) {
      for (Path file : entries.filter(Files::isRegularFile).toList()) {
        String fileName = file.getFileName().toString();
        if (!fileName.endsWith(".jar") || fileName.endsWith("-sources.jar")) continue;
        for (String name : NAMES) {
          String prefix = name + "-";
          if (!fileName.startsWith(prefix)) continue;
          String version = fileName.substring(prefix.length(), fileName.length() - ".jar".length());
          if (version.isEmpty()) continue;
          Found existing = found.get(name);
          if (existing == null || compare(version, existing.version()) > 0) found.put(name, new Found(version, file));
          break;
        }
      }
    }
    return found;
  }

  /** The file name a jar of this artifact and version has, in every repository and on disk. */
  public static String fileName(String artifact, String version) {
    return artifact + "-" + version + ".jar";
  }

  /** The repository path of a jar, relative to a Maven repository root. */
  public static String repositoryPath(String artifact, String version) {
    return GROUPS.get(artifact) + "/" + artifact + "/" + version + "/" + fileName(artifact, version);
  }

  /** The repository path of an artifact's metadata, relative to a Maven repository root. */
  public static String metadataPath(String artifact) {
    return GROUPS.get(artifact) + "/" + artifact + "/maven-metadata.xml";
  }

  /**
   * Compares two versions by their dotted numbers, left to right, with a trailing qualifier ranking
   * below the same version without one.
   *
   * <p>So 5.12.0 beats 5.11.0, and 5.12.0 beats 5.12.0-SNAPSHOT. The comparison is deliberately dumb:
   * it is used to answer "is this newer", and anything it cannot read compares as older, which fails
   * towards keeping what already works.
   */
  public static int compare(String left, String right) {
    List<Integer> a = numbers(left);
    List<Integer> b = numbers(right);
    for (int index = 0; index < Math.max(a.size(), b.size()); index++) {
      int one = index < a.size() ? a.get(index) : 0;
      int two = index < b.size() ? b.get(index) : 0;
      if (one != two) return Integer.compare(one, two);
    }
    // Same numbers: a release outranks anything with a qualifier after it.
    return Integer.compare(qualified(left) ? 0 : 1, qualified(right) ? 0 : 1);
  }

  /** The first component, which is the line an update must stay inside. */
  public static int major(String version) {
    List<Integer> numbers = numbers(version);
    return numbers.isEmpty() ? -1 : numbers.get(0);
  }

  /** Whether this names a release rather than a snapshot or a pre-release. */
  public static boolean isRelease(String version) {
    return !qualified(version) && !numbers(version).isEmpty();
  }

  private static boolean qualified(String version) {
    for (int index = 0; index < version.length(); index++) {
      char current = version.charAt(index);
      if (current != '.' && (current < '0' || current > '9')) return true;
    }
    return false;
  }

  private static List<Integer> numbers(String version) {
    List<Integer> numbers = new ArrayList<>(3);
    StringBuilder digits = new StringBuilder();
    for (int index = 0; index < version.length(); index++) {
      char current = version.charAt(index);
      if (current >= '0' && current <= '9') {
        digits.append(current);
      } else if (current == '.') {
        if (digits.isEmpty()) break;
        numbers.add(Integer.parseInt(digits.toString()));
        digits.setLength(0);
      } else {
        // A qualifier starts here, and everything after it is not a number.
        break;
      }
    }
    if (!digits.isEmpty()) numbers.add(Integer.parseInt(digits.toString()));
    return numbers;
  }
}
