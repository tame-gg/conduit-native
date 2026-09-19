// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.boot;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The jar's entry point. Decides which ViaVersion the proxy runs on, then starts the proxy.
 *
 * <p>Via is merged into the jar so translation works offline with nothing to fetch, but a jar cannot
 * update itself and Via supports each new Minecraft release well before a Conduit release can. So
 * {@code lib/via} beside the configuration is an override: a complete, newer set of Via jars there is
 * used in place of the bundled copies, whether an operator put them there or {@link ViaUpdater} did.
 *
 * <p>The override works by class path order rather than by unpacking anything. A child loader is built
 * over {@code [the override jars, then everything that was already on the class path]} with the
 * <em>platform</em> loader as its parent, not the application loader -- delegation goes to the parent
 * first, so a child whose parent could see the jar would find the bundled Via there and the override
 * would never be reached. The whole original class path is carried across, so that nothing else on
 * it -- the Velocity API merged into the jar included -- is lost in the process.
 *
 * <p>Nothing in this package may touch a Conduit or Via class: see {@link ViaArtifacts}. It reads the
 * configuration with {@link BootConfig} rather than the real loader for the same reason.
 *
 * <p>When there is no override -- the ordinary case -- no loader is built at all and the proxy is
 * called directly. A mechanism that is not needed should not be in the way.
 */
public final class Bootstrap {
  /** Where the proxy actually starts. */
  private static final String MAIN = "gg.tame.conduit.launcher.Main";
  /** What the configuration is called when the command line does not say. */
  private static final String DEFAULT_CONFIG = "conduit.toml";

  private Bootstrap() {}

  public static void main(String[] arguments) throws Exception {
    // A bare `java -jar conduit.jar` is someone who has just downloaded it. Defaulting the path means
    // that starts a proxy rather than printing a usage line at them.
    boolean checkOnly = arguments.length > 0 && arguments[0].equals("--check-config");
    String[] forwarded = arguments;
    if (arguments.length == (checkOnly ? 1 : 0)) {
      forwarded = checkOnly ? new String[] { "--check-config", DEFAULT_CONFIG } : new String[] { DEFAULT_CONFIG };
    }
    Path configPath = Path.of(forwarded[checkOnly ? 1 : 0]).toAbsolutePath();
    Path directory = configPath.getParent() == null ? Path.of(".").toAbsolutePath() : configPath.getParent();
    Path viaDirectory = directory.resolve("lib").resolve("via");

    // --check-config must not touch the network or the disk, so the whole of this is skipped for it.
    if (!checkOnly) {
      try {
        prepareVia(viaDirectory, configPath);
      } catch (IOException | RuntimeException failure) {
        // The bundled Via is still there, so this is never fatal.
        say("WARN", "Could not check for a ViaVersion update: " + failure);
      }
    }

    List<URL> override = overrideJars(viaDirectory, checkOnly);
    if (override.isEmpty()) {
      Class.forName(MAIN).getMethod("main", String[].class).invoke(null, (Object) forwarded);
      return;
    }
    launchWith(override, forwarded);
  }

  /** Runs the updater when the configuration asks for it, and says what it found. */
  private static void prepareVia(Path viaDirectory, Path configPath) throws IOException {
    BootConfig config = BootConfig.read(configPath);
    if (!config.viaUpdates()) return;
    Map<String, String> have = effectiveVersions(viaDirectory);
    ViaUpdater.Outcome outcome = ViaUpdater.update(viaDirectory, have, config.checkOnly(), config.timeoutMs());
    switch (outcome.kind()) {
      // superseded/ is only mentioned when something was actually put there. On a first update the
      // set being replaced is the one inside the jar, so there is nothing on disk to move, and
      // pointing at an empty directory sends an operator looking for files that were never written.
      case UPDATED -> say("INFO", "Updated ViaVersion in " + viaDirectory + ": " + outcome.detail()
          + (holdsFiles(viaDirectory.resolve("superseded"))
              ? ". The jars it replaced are in " + viaDirectory.resolve("superseded") + "."
              : "."));
      case AVAILABLE -> say("INFO", "A newer ViaVersion is available (" + outcome.detail()
          + "). updates.check-only is true, so nothing was downloaded.");
      case SKIPPED -> say("WARN", outcome.detail());
      case FAILED -> say("WARN", "ViaVersion update check failed (" + outcome.detail()
          + "). Carrying on with the ViaVersion already in use.");
      case UP_TO_DATE -> { /* The ordinary case, and not worth a line every start. */ }
    }
  }

  /**
   * The versions in effect right now: whatever {@code lib/via} holds, falling back per artifact to the
   * bundled copy.
   *
   * <p>Per artifact rather than all or nothing, so a directory holding only ViaLegacy is compared
   * honestly instead of making the other four look absent.
   */
  private static Map<String, String> effectiveVersions(Path viaDirectory) throws IOException {
    Map<String, String> versions = new LinkedHashMap<>(ViaArtifacts.bundled());
    ViaArtifacts.inDirectory(viaDirectory).forEach((artifact, found) -> {
      if (ViaArtifacts.compare(found.version(), versions.get(artifact)) > 0) versions.put(artifact, found.version());
    });
    return versions;
  }

  /**
   * The override jars, in class path order, or nothing at all.
   *
   * <p>Three things have to hold before the bundled Via is displaced, and any one of them failing means
   * the jar's own copy is used:
   *
   * <ul>
   *   <li>Every one of the five artifacts is present. A set missing one resolves that one from the jar,
   *       and an updated {@code viaversion-common} against a bundled {@code viaversion-api} is the
   *       combination that does not work.
   *   <li>At least one is newer than what is bundled. A stale {@code lib/via} left over from an older
   *       Conduit would otherwise be a silent downgrade.
   *   <li>None is a different major version. The proxy extends internal Via classes, and a major bump
   *       is where those change.
   * </ul>
   */
  private static List<URL> overrideJars(Path viaDirectory, boolean quiet) {
    try {
      Map<String, ViaArtifacts.Found> found = ViaArtifacts.inDirectory(viaDirectory);
      if (found.isEmpty()) return List.of();
      Map<String, String> bundled = ViaArtifacts.bundled();

      List<String> missing = new ArrayList<>();
      for (String artifact : ViaArtifacts.NAMES) if (!found.containsKey(artifact)) missing.add(artifact);
      if (!missing.isEmpty()) {
        if (!quiet) {
          say("WARN", viaDirectory + " has some ViaVersion jars but not all of them (missing "
              + String.join(", ", missing) + "), so the ones in the jar are used instead. A partial set"
              + " cannot be mixed with the bundled one.");
        }
        return List.of();
      }

      boolean newer = false;
      for (String artifact : ViaArtifacts.NAMES) {
        String version = found.get(artifact).version();
        String have = bundled.get(artifact);
        if (ViaArtifacts.major(version) != ViaArtifacts.major(have)) {
          if (!quiet) {
            say("WARN", viaDirectory + " holds " + artifact + " " + version + ", a different major version"
                + " from the bundled " + have + ". This Conduit is built against " + have + ", so the"
                + " bundled ViaVersion is used. Remove it from lib/via to silence this.");
          }
          return List.of();
        }
        if (ViaArtifacts.compare(version, have) > 0) newer = true;
      }
      if (!newer) return List.of();

      List<URL> urls = new ArrayList<>(ViaArtifacts.NAMES.size());
      List<String> names = new ArrayList<>(ViaArtifacts.NAMES.size());
      for (String artifact : ViaArtifacts.NAMES) {
        ViaArtifacts.Found jar = found.get(artifact);
        urls.add(jar.file().toUri().toURL());
        names.add(artifact + " " + jar.version());
      }
      if (!quiet) say("INFO", "Using the ViaVersion in " + viaDirectory + " instead of the bundled one: "
          + String.join(", ", names) + ".");
      return urls;
    } catch (IOException | RuntimeException failure) {
      say("WARN", "Could not read " + viaDirectory + " (" + failure + "); using the bundled ViaVersion.");
      return List.of();
    }
  }

  /**
   * Builds the loader the override needs and starts the proxy in it.
   *
   * <p>The loader is not closed: it owns the classes of a running proxy for as long as the process
   * lives, and the process ends when the proxy does.
   */
  private static void launchWith(List<URL> override, String[] arguments) throws Exception {
    List<URL> urls = new ArrayList<>(override);
    for (String entry : System.getProperty("java.class.path", "").split(java.io.File.pathSeparator)) {
      if (entry.isBlank()) continue;
      try {
        Path path = Path.of(entry).toAbsolutePath();
        // Kept whether or not it exists today: an entry that is only a directory still belongs on the
        // class path, and a missing one is the JVM's to ignore as it already did.
        urls.add(path.toUri().toURL());
      } catch (RuntimeException | java.net.MalformedURLException skip) {
        say("WARN", "Ignoring unreadable class path entry " + entry);
      }
    }
    // The platform loader, not the application loader: delegation asks the parent first, so a parent
    // that can see the jar would answer every Via class from the bundled copy and the override would
    // be dead weight.
    URLClassLoader loader = new URLClassLoader("conduit", urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    Thread.currentThread().setContextClassLoader(loader);
    Class<?> main = Class.forName(MAIN, true, loader);
    try {
      main.getMethod("main", String[].class).invoke(null, (Object) arguments);
    } catch (java.lang.reflect.InvocationTargetException thrown) {
      // Unwrapped, so the proxy's own failure is what the operator sees rather than a reflection frame.
      Throwable cause = thrown.getCause();
      if (cause instanceof Exception checked) throw checked;
      if (cause instanceof Error error) throw error;
      throw thrown;
    }
  }

  /**
   * Logging, before the logger exists.
   *
   * <p>{@code ConduitLog} is a Conduit class and loading it here would load it in the wrong loader, so
   * these few lines are written by hand in the same shape: the stamp and level in brackets, then the
   * message, warnings on stderr. It has to be kept in step with {@code ConduitLog.prefix} by hand,
   * which is worth it for a console that does not change format two lines in. Colour is left out
   * rather than reimplemented -- this is at most a couple of lines a start.
   */
  private static void say(String level, String message) {
    String stamp = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss zzz")
        .withZone(java.time.ZoneId.systemDefault())
        .format(java.time.Instant.now());
    String line = "[" + stamp + " " + level + "]: " + message;
    if (level.equals("WARN")) System.err.println(line);
    else System.out.println(line);
  }

  /** Whether a directory holds at least one file, used only to keep the messages honest. */
  private static boolean holdsFiles(Path directory) {
    if (!Files.isDirectory(directory)) return false;
    try (var entries = Files.list(directory)) {
      return entries.anyMatch(Files::isRegularFile);
    } catch (IOException unreadable) {
      return false;
    }
  }
}
