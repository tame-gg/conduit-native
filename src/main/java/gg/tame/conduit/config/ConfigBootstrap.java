// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.config;

import gg.tame.conduit.log.ConduitLog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Makes a bare folder into a folder Conduit can start in.
 *
 * <p>Downloading the jar and running it used to end at {@code conduit.toml does not exist}, with the
 * sample somewhere else entirely -- a first-run failure for something Conduit could do itself, since
 * it ships the file it was asking for. A first start now writes the configuration, creates the
 * directories it needs, and comes up on the defaults.
 *
 * <p>What it will not do is write a configuration somewhere the operator did not ask for. A path given
 * on the command line is taken as given: if its folder does not exist, that is a typo far more often
 * than it is a request, and creating it would hide the typo.
 */
public final class ConfigBootstrap {
  private ConfigBootstrap() {}

  /** What a first start created, so the caller can say so once rather than per directory. */
  public record Result(boolean createdConfig, java.util.List<String> createdDirectories) {
    public Result {
      createdDirectories = java.util.List.copyOf(createdDirectories);
    }
  }

  /**
   * Ensures {@code configPath} and the directories beside it exist.
   *
   * @param write false to check without creating anything, which is what {@code --check-config} needs:
   *     validating a configuration must not write to the folder it is validating
   */
  public static Result ensure(Path configPath, boolean write) throws IOException {
    Path directory = configPath.toAbsolutePath().getParent();
    if (directory == null) throw new IOException("cannot work out which folder " + configPath + " is in");
    if (!write) return new Result(false, java.util.List.of());
    if (!Files.isDirectory(directory)) {
      // Named by the operator, so it is theirs to create; see the class comment.
      throw new IOException(directory + " does not exist. Create it, or start Conduit in the folder you"
          + " want it to run in.");
    }

    boolean createdConfig = false;
    if (!Files.exists(configPath)) {
      // CREATE_NEW: two Conduits started together in one folder would otherwise both write, and the
      // second would overwrite a file the first had already begun reading.
      try {
        Files.writeString(configPath, ConfigTemplate.text(), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        createdConfig = true;
      } catch (java.nio.file.FileAlreadyExistsException raced) {
        createdConfig = false;
      }
    }

    java.util.List<String> created = new java.util.ArrayList<>();
    // plugins/ only. The Via data folder is ConduitViaBootstrap's, made from the configured name,
    // and dumps/ is made by the command that writes one -- neither is knowable from here.
    if (createDirectory(directory.resolve("plugins"))) created.add("plugins");

    Result result = new Result(createdConfig, created);
    report(configPath, result);
    return result;
  }

  private static boolean createDirectory(Path path) throws IOException {
    if (Files.isDirectory(path)) return false;
    if (Files.exists(path)) {
      // A file where a directory belongs. Worth a word: plugins would silently never load.
      ConduitLog.warn(path + " is a file, not a folder, so nothing there will be loaded.");
      return false;
    }
    Files.createDirectories(path);
    return true;
  }

  private static void report(Path configPath, Result result) {
    if (result.createdConfig()) {
      ConduitLog.info("First start: wrote " + configPath.getFileName() + " with Conduit's defaults."
          + " Authentication is online and forwarding is off, so add your backends under [servers.*]"
          + " and set forwarding.mode = \"modern\" before letting anyone in.");
    }
    if (!result.createdDirectories().isEmpty()) {
      ConduitLog.info("Created " + String.join(", ", result.createdDirectories()) + " beside "
          + configPath.getFileName() + ".");
    }
  }
}
