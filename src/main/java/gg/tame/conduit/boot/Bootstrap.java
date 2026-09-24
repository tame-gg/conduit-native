// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.boot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
      // First, so the ViaVersion lines below are in logs/latest.log with everything after them.
      gg.tame.conduit.log.ConduitFileLog.install(directory.resolve("logs"));
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

  /** Installs ViaVersion when there is none, then updates it when the configuration asks. */
  private static void prepareVia(Path viaDirectory, Path configPath) throws IOException {
    BootConfig config = BootConfig.read(configPath);
    if (!ViaArtifacts.inDirectory(viaDirectory).keySet().containsAll(ViaArtifacts.NAMES) && !viaInsideJar()) {
      // Nothing to translate with. Conduit does not carry Via -- see ViaUpdater#install -- so this
      // is what an unpacked-and-started copy looks like, and it is where the jars come from.
      if (!config.viaUpdates()) {
        say("WARN", "ViaVersion is not in " + viaDirectory + " and updates.via is false, so it will not be"
            + " downloaded. Cross-version play is off until the jars are there.");
        return;
      }
      Map<String, String> pinned = ViaArtifacts.bundled();
      say("INFO", "No ViaVersion in " + viaDirectory + ", so Conduit is installing the set this build is pinned to"
          + " (" + describeVersions(pinned) + ") from repo.viaversion.com, checked against the hashes it carries...");
      ViaUpdater.Outcome installed = ViaUpdater.install(viaDirectory, pinned, ViaArtifacts.bundledHashes(), config.timeoutMs());
      if (installed.kind() == ViaUpdater.Outcome.Kind.FAILED) {
        say("WARN", "Could not install ViaVersion (" + installed.detail() + "). Conduit starts without it, so a"
            + " client may only join a backend on its own protocol. Put the jars in " + viaDirectory
            + " yourself, or start again with the repository reachable.");
        return;
      }
      say("INFO", "Installed ViaVersion: " + installed.detail());
    }
    if (!config.viaUpdates()) return;
    // Nothing else between starting Conduit and Conduit listening waits on somebody else's server.
    // A check that finds a release downloads some ten megabytes before the proxy binds, and one that
    // cannot reach the repository waits out timeout-ms -- both on every restart, for an answer that
    // changes every few weeks. A check is therefore remembered, and one made within the interval is
    // not made again.
    if (checkedRecently(viaDirectory, config.checkIntervalHours())) return;
    Map<String, String> have = effectiveVersions(viaDirectory);
    ViaUpdater.Outcome outcome = ViaUpdater.update(viaDirectory, have, config.checkOnly(), config.timeoutMs());
    // A check that reached the repository is remembered; one that failed is not, so an unreachable
    // network is retried on the next start rather than held off for the interval.
    if (outcome.kind() != ViaUpdater.Outcome.Kind.FAILED) rememberCheck(viaDirectory);
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

  /** Where the time of the last check that reached repo.viaversion.com is kept. */
  private static Path checkMarker(Path viaDirectory) { return viaDirectory.resolve(".last-update-check"); }

  /**
   * Whether a check was made recently enough to stand in for this one. An interval of 0 means every
   * start checks, which is how Conduit behaved before the marker existed. A marker that cannot be
   * read, or holds something that is not a time, is treated as no marker at all: the cost of an
   * extra check is one round trip, and the cost of trusting a bad one is never updating again.
   */
  private static boolean checkedRecently(Path viaDirectory, int intervalHours) {
    if (intervalHours <= 0) return false;
    try {
      Path marker = checkMarker(viaDirectory);
      if (!Files.isRegularFile(marker)) return false;
      long checkedAt = Long.parseLong(Files.readString(marker, StandardCharsets.UTF_8).strip());
      long age = System.currentTimeMillis() - checkedAt;
      // A marker from the future is a clock that moved, not a check that has not happened yet.
      return age >= 0 && age < intervalHours * 3_600_000L;
    } catch (IOException | RuntimeException unreadable) {
      return false;
    }
  }

  /** Records that the repository answered, so the next start within the interval need not ask. */
  private static void rememberCheck(Path viaDirectory) {
    try {
      Files.writeString(checkMarker(viaDirectory), Long.toString(System.currentTimeMillis()),
          StandardCharsets.UTF_8);
    } catch (IOException | RuntimeException unwritable) {
      // A marker that cannot be written costs a check next start, which is what used to happen
      // every start. Not worth a line in the log, and certainly not worth failing a start over.
    }
  }

  /**
   * Whether a ViaVersion is inside this jar after all, asked by resource and not by class, since
   * loading one here would load it in the wrong loader. Old builds merged it in; this one does not,
   * and the check keeps such a build working rather than downloading what it already has.
   */
  private static boolean viaInsideJar() {
    return Bootstrap.class.getResource("/com/viaversion/viaversion/api/Via.class") != null;
  }

  /** "viaversion-api 5.11.0, ..." for a line in the log. */
  private static String describeVersions(Map<String, String> versions) {
    List<String> parts = new ArrayList<>();
    versions.forEach((artifact, version) -> parts.add(artifact + " " + version));
    return String.join(", ", parts);
  }

  /**
   * The versions in effect right now: whatever {@code lib/via} holds, falling back per artifact to the
   * bundled copy.
   *
   * <p>Per artifact rather than all or nothing, so a directory holding only ViaLegacy is compared
   * honestly instead of making the other four look absent.
   */
  private static Map<String, String> effectiveVersions(Path viaDirectory) throws IOException {
    // The pinned set is the floor only when the jar really carries it; otherwise what is on disk is
    // all there is, and pretending otherwise would make an update look unnecessary.
    Map<String, String> versions = viaInsideJar() ? new LinkedHashMap<>(ViaArtifacts.bundled()) : new LinkedHashMap<>();
    ViaArtifacts.inDirectory(viaDirectory).forEach((artifact, found) -> {
      // With nothing in the jar there is nothing to compare against, and what is on disk is simply
      // what is in effect. Comparing against an absent version is how a first start walked into a
      // NullPointerException and lost its update check.
      String have = versions.get(artifact);
      if (have == null || ViaArtifacts.compare(found.version(), have) > 0) versions.put(artifact, found.version());
    });
    return versions;
  }

  /**
   * The override jars, in class path order, or nothing at all.
   *
   * <p>This is where ViaVersion comes from: the jar carries none, so a complete set here is the Via
   * the proxy runs on. Two things have to hold, and either failing means the proxy runs without Via
   * -- every player still reaches a backend on their own protocol, and only cross-version play is
   * off:
   *
   * <ul>
   *   <li>Every one of the five artifacts is present. An updated {@code viaversion-common} against an
   *       older {@code viaversion-api} is the combination that does not work, and a set missing one
   *       has nothing to fall back to now.
   *   <li>None is a different major version from the set this build pins. The proxy extends internal
   *       Via classes, and a major bump is where those change.
   * </ul>
   *
   * <p>An older build that merged Via into the jar still works the way it always did: there the
   * pinned set really is inside the jar, so a set here must also be newer than it before it is used.
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
              + String.join(", ", missing) + "), so none of them are used: a partial set cannot be mixed"
              + " with another. Delete the directory to have Conduit install a whole one.");
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
                + " from the " + have + " this Conduit is built against, so it is not used. Put the " + have
                + " line back in lib/via, or update Conduit.");
          }
          return List.of();
        }
        if (ViaArtifacts.compare(version, have) > 0) newer = true;
      }
      // "Newer than the jar's copy" only means something when the jar has one. Unbundled, a set that
      // matches the pinned versions exactly is the ordinary case: it is what the first start
      // installed.
      if (!newer && viaInsideJar()) return List.of();

      List<URL> urls = new ArrayList<>(ViaArtifacts.NAMES.size());
      List<String> names = new ArrayList<>(ViaArtifacts.NAMES.size());
      for (String artifact : ViaArtifacts.NAMES) {
        ViaArtifacts.Found jar = found.get(artifact);
        urls.add(jar.file().toUri().toURL());
        names.add(artifact + " " + jar.version());
      }
      if (!quiet) say("INFO", "ViaVersion from " + viaDirectory + ": "
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
