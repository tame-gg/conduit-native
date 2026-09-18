// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Temporary files and directories for tests. Everything is deleted when the JVM exits, whether
 * the tests passed or failed.
 *
 * <p>Each {@link #file} is created inside a new directory of its own. A runtime that uses the
 * file's parent as its configuration directory therefore writes its plugins, dumps and Via data
 * there, and not loose into the system temp directory. If something cannot be deleted, the
 * reason is printed. On Windows that usually means a handle is still open, for example a jar held
 * by a class loader nobody closed, and that is a bug to fix, not to hide.
 */
public final class TempFiles {
  private static final Set<Path> CREATED = ConcurrentHashMap.newKeySet();

  static {
    Runtime.getRuntime().addShutdownHook(new Thread(TempFiles::deleteAll, "test-temp-cleanup"));
  }

  private TempFiles() { }

  /** A new empty directory, like {@code Files.createTempDirectory(prefix)}. */
  public static Path dir(String prefix) throws IOException {
    Path dir = Files.createTempDirectory(prefix);
    CREATED.add(dir);
    return dir;
  }

  /** A new empty file named {@code prefix + suffix}, alone in a new directory. */
  public static Path file(String prefix, String suffix) throws IOException {
    return Files.createFile(dir(prefix).resolve(prefix + suffix));
  }

  /** Deletes everything created so far. The shutdown hook calls this. */
  static void deleteAll() {
    for (Path root : CREATED) {
      try (Stream<Path> tree = Files.walk(root)) {
        tree.sorted(Comparator.reverseOrder()).forEach(TempFiles::delete);
      } catch (IOException | UncheckedIOException gone) {
        if (Files.exists(root)) System.err.println("TempFiles: could not walk " + root + ": " + gone);
      }
      CREATED.remove(root);
    }
  }

  private static void delete(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException held) {
      System.err.println("TempFiles: could not delete " + path + ": " + held);
    }
  }
}
