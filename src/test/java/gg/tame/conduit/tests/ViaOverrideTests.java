// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.boot.BootConfig;
import gg.tame.conduit.boot.ViaArtifacts;
import gg.tame.conduit.config.ConfigurationLoader;
import gg.tame.conduit.config.UpdateSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The bootstrap's side of ViaVersion: which set of jars wins, and the settings that decide whether one
 * is fetched at all.
 *
 * <p>These are the rules that keep an updater from being worse than no updater. Getting the comparison
 * wrong means a stale {@code lib/via} silently downgrades Via, or a partial set puts an updated
 * {@code viaversion-common} on the class path against the bundled {@code viaversion-api} -- the one
 * combination guaranteed not to work. None of that shows up as a crash; it shows up as a client that
 * cannot join, weeks later.
 */
public final class ViaOverrideTests {
  public static void main(String[] arguments) throws Exception { run(); }

  public static void run() throws Exception {
    versionsCompareByNumber();
    theBundledSetIsNamedAndComplete();
    theBundledVersionsMatchWhatTheBuildFetches();
    aDirectoryIsReadAsAVersionedSet();
    theBootstrapAndTheLoaderReadTheSameSettings();
    System.out.println("ViaOverrideTests OK");
  }

  /** Newer is newer, a qualifier is older, and anything unreadable ranks last. */
  private static void versionsCompareByNumber() {
    require(ViaArtifacts.compare("5.12.0", "5.11.0") > 0, "5.12.0 is newer than 5.11.0");
    require(ViaArtifacts.compare("5.11.0", "5.12.0") < 0, "and the other way round");
    require(ViaArtifacts.compare("5.11.0", "5.11.0") == 0, "the same version is the same");
    // 5.9.0 against 5.10.0 is where a string comparison gets it wrong, and would refuse an update.
    require(ViaArtifacts.compare("5.10.0", "5.9.0") > 0, "10 is newer than 9, not older");
    require(ViaArtifacts.compare("4.10.2", "5.0.0") < 0, "the major leads");
    // A snapshot must never displace the release of the same version.
    require(ViaArtifacts.compare("5.12.0", "5.12.0-SNAPSHOT") > 0, "a release beats its own snapshot");
    require(!ViaArtifacts.isRelease("5.12.0-SNAPSHOT"), "a snapshot is not a release");
    require(!ViaArtifacts.isRelease("nonsense"), "and nor is a version with no numbers at all");
    require(ViaArtifacts.isRelease("3.0.16"), "a plain version is a release");
    require(ViaArtifacts.major("5.12.0") == 5 && ViaArtifacts.major("4.1.3") == 4, "the major is the first number");
  }

  /** The bundled manifest names every artifact, because a missing one has no version to compare. */
  private static void theBundledSetIsNamedAndComplete() throws Exception {
    Map<String, String> bundled = ViaArtifacts.bundled();
    require(bundled.size() == ViaArtifacts.NAMES.size(), "every artifact has a bundled version");
    for (String artifact : ViaArtifacts.NAMES) {
      require(ViaArtifacts.isRelease(bundled.get(artifact)), artifact + " names a release version");
      require(ViaArtifacts.GROUPS.containsKey(artifact), artifact + " has a repository group");
    }
    // The api before the common that implements it: class path order is the whole mechanism.
    require(ViaArtifacts.NAMES.indexOf("viaversion-api") < ViaArtifacts.NAMES.indexOf("viaversion-common"),
        "the api comes before the common");
  }

  /**
   * The bundled versions are the ones the build actually merges into the jar.
   *
   * <p>They live in two files -- this manifest and {@code scripts/fetch-via.ps1}, which downloads the
   * jars -- and nothing but this test stops them drifting. Drifted, the bootstrap compares against a
   * version that is not in the jar, and either refuses a real update or takes a downgrade.
   */
  private static void theBundledVersionsMatchWhatTheBuildFetches() throws Exception {
    Path script = Path.of("scripts", "fetch-via.ps1");
    if (!Files.isRegularFile(script)) {
      System.out.println("  fetch-via.ps1 not beside the working directory, skipping the drift check");
      return;
    }
    String text = Files.readString(script);
    Map<String, String> bundled = ViaArtifacts.bundled();
    // The script names each jar as "<artifact>-$<variable>.jar" and sets the variables above, so the
    // file names it builds are what to look for rather than the variables.
    for (Map.Entry<String, String> entry : bundled.entrySet()) {
      String fileName = ViaArtifacts.fileName(entry.getKey(), entry.getValue());
      String variable = switch (entry.getKey()) {
        case "viarewind-common" -> "viaRewind";
        case "ViaLegacy" -> "viaLegacy";
        default -> "viaVersion";
      };
      require(text.contains("$" + variable + " = \"" + entry.getValue() + "\""),
          "fetch-via.ps1 pins $" + variable + " to " + entry.getValue() + ", which via-bundled.properties"
              + " claims is bundled (" + fileName + ")");
    }
  }

  /** A directory of jars reads back as artifact to version, newest per artifact, top level only. */
  private static void aDirectoryIsReadAsAVersionedSet() throws Exception {
    Path directory = TempFiles.dir("conduit-via-override");
    require(ViaArtifacts.inDirectory(directory).isEmpty(), "an empty directory is an empty set");
    require(ViaArtifacts.inDirectory(directory.resolve("absent")).isEmpty(), "and so is one that is not there");

    for (String name : List.of("viaversion-api-5.12.0.jar", "viaversion-common-5.12.0.jar",
        "viabackwards-common-5.12.0.jar", "viarewind-common-4.2.0.jar", "ViaLegacy-3.1.0.jar")) {
      Files.writeString(directory.resolve(name), "");
    }
    Map<String, ViaArtifacts.Found> found = ViaArtifacts.inDirectory(directory);
    require(found.size() == 5, "all five are found, got " + found.keySet());
    require(found.get("viaversion-common").version().equals("5.12.0"), "the version comes off the file name");
    require(found.get("ViaLegacy").version().equals("3.1.0"), "including one on its own version line");

    // Two versions of one artifact would put both on the class path; the newer has to win.
    Files.writeString(directory.resolve("viaversion-common-5.11.0.jar"), "");
    require(ViaArtifacts.inDirectory(directory).get("viaversion-common").version().equals("5.12.0"),
        "the newer of two copies wins");

    // superseded/ is how an update keeps the jars it replaced without keeping them loadable.
    Path superseded = Files.createDirectories(directory.resolve("superseded"));
    Files.writeString(superseded.resolve("viaversion-common-9.9.9.jar"), "");
    require(ViaArtifacts.inDirectory(directory).get("viaversion-common").version().equals("5.12.0"),
        "a subdirectory is not on the class path, so it is not considered");

    // A sources jar is not a class path entry, and its name would parse as a version.
    Files.writeString(directory.resolve("viaversion-api-5.12.0-sources.jar"), "");
    require(ViaArtifacts.inDirectory(directory).get("viaversion-api").version().equals("5.12.0"),
        "a sources jar is ignored rather than read as a version");
  }

  /**
   * The bootstrap reads {@code [updates]} with its own scanner, because it runs before the real loader
   * can be loaded. Two readers of one file is a drift risk, so they are checked against each other.
   */
  private static void theBootstrapAndTheLoaderReadTheSameSettings() throws Exception {
    Path directory = TempFiles.dir("conduit-boot-config");
    Path file = directory.resolve("conduit.toml");

    // Absent: the defaults, which are what a first start is about to write anyway.
    require(BootConfig.read(file).viaUpdates() == UpdateSettings.DEFAULT_VIA, "a missing file means the defaults");
    require(BootConfig.read(file).timeoutMs() == UpdateSettings.DEFAULT_TIMEOUT_MS, "including the timeout");

    String base = """
        [listener]
        host = "127.0.0.1"
        port = 25565
        max-frame-bytes = 1048576
        [forwarding]
        mode = "none"
        [servers.lobby]
        host = "127.0.0.1"
        port = 25566
        [routing]
        initial = ["lobby"]
        fallback = ["lobby"]
        """;
    Files.writeString(file, base + """
        [updates]
        via = false
        check-only = true
        timeout-ms = 1500
        """);
    BootConfig boot = BootConfig.read(file);
    UpdateSettings loaded = ConfigurationLoader.load(file).ops().updates();
    require(boot.viaUpdates() == loaded.via() && !loaded.via(), "both readers see via = false");
    require(boot.checkOnly() == loaded.checkOnly() && loaded.checkOnly(), "both see check-only = true");
    require(boot.timeoutMs() == loaded.timeoutMs() && loaded.timeoutMs() == 1500, "both see the timeout");

    // A trailing comment is TOML, and the bootstrap's scanner has to know that too.
    Files.writeString(file, base + "[updates]\nvia = false # not on this box\n");
    require(!BootConfig.read(file).viaUpdates(), "a trailing comment is not part of the value");
    require(!ConfigurationLoader.load(file).ops().updates().via(), "and the real loader agrees");

    // An out-of-range value keeps the default here rather than refusing to start; the real loader is
    // what reports it, with the line number.
    Files.writeString(file, base + "[updates]\ntimeout-ms = 99999999\n");
    require(BootConfig.read(file).timeoutMs() == UpdateSettings.DEFAULT_TIMEOUT_MS,
        "the bootstrap keeps the default for a value out of range");
    try {
      ConfigurationLoader.load(file);
      throw new AssertionError("the real loader accepted a timeout out of range");
    } catch (IllegalArgumentException expected) {
      require(String.valueOf(expected.getMessage()).contains("updates.timeout-ms"), "and names the setting");
    }

    // [updates] in another section's shadow must not be read as this one's.
    Files.writeString(file, base + "[health]\nvia = false\n");
    require(BootConfig.read(file).viaUpdates(), "a key of the same name in another section is not [updates]");

    // -Dconduit.via.update beats the file, and has to work on a first start too. A file that did not
    // exist yet returned the defaults directly and skipped the override, so the one switch for
    // "bring this up without touching the network" did nothing on the very start that needed it.
    Path absent = directory.resolve("not-written-yet.toml");
    System.setProperty("conduit.via.update", "false");
    try {
      require(!BootConfig.read(absent).viaUpdates(), "the property applies when there is no file yet");
      Files.writeString(file, base + "[updates]\nvia = true\n");
      require(!BootConfig.read(file).viaUpdates(), "and it beats an explicit true in the file");
      System.setProperty("conduit.via.update", "true");
      Files.writeString(file, base + "[updates]\nvia = false\n");
      require(BootConfig.read(file).viaUpdates(), "in both directions");
    } finally {
      System.clearProperty("conduit.via.update");
    }
    require(BootConfig.read(absent).viaUpdates() == UpdateSettings.DEFAULT_VIA, "and is gone once cleared");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
