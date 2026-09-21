// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.boot;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Looks for a newer ViaVersion release and puts it in {@code lib/via} for the bootstrap to prefer.
 *
 * <p>Via adds support for each new Minecraft release long before a Conduit release can, and this is
 * how an operator gets that without waiting: Via 5.12.0 registers Minecraft 26.3, which the Via inside
 * the jar does not. Conduit compiles and its whole translation suite passes against 5.12.0, so moving
 * within a version line is a supported thing to do rather than a gamble.
 *
 * <p><strong>Nothing here may fail a start.</strong> A repository that is down, a proxy that blocks
 * it, a disk that is full and a checksum that does not match all end the same way: a line in the log
 * and the bundled Via. An auto-updater that can stop the proxy coming up is worse than no updater, so
 * every path out of this class is "carry on with what we had".
 *
 * <p>Three rules keep an update from being a downgrade or a surprise:
 *
 * <ul>
 *   <li><strong>Releases only.</strong> The repository lists snapshots and pre-releases too; only
 *       {@code <release>} is considered.
 *   <li><strong>Inside the version line.</strong> A new major means changes to the internal Via
 *       classes {@code gg.tame.conduit.viaversion} extends, which is exactly what Conduit cannot
 *       assume still fits. A major bump is reported and not taken.
 *   <li><strong>The whole set or none of it.</strong> Mixing an updated {@code viaversion-common}
 *       with the bundled {@code viaversion-api} is the one combination guaranteed not to work, so
 *       every artifact is resolved and verified before any of them is moved into place.
 * </ul>
 *
 * <p>Plain JDK only; see {@link ViaArtifacts} for why.
 */
public final class ViaUpdater {
  /**
   * ViaVersion's own repository, which is where these artifacts are published.
   *
   * <p>{@code -Dconduit.via.repository} points this at a Maven repository of the same layout, for a
   * network that reaches an internal mirror and not the internet. It is also how the unreachable case
   * gets tested, which is the path that matters most here.
   */
  private static final String REPOSITORY = repository();

  private static String repository() {
    String override = System.getProperty("conduit.via.repository");
    if (override == null || override.isBlank()) return "https://repo.viaversion.com/everything";
    // A trailing slash would double up in every path built from this.
    return override.endsWith("/") ? override.substring(0, override.length() - 1) : override.strip();
  }
  /** Names Conduit and nothing else: no user, no machine, no path. */
  private static final String USER_AGENT = "Conduit/0.9.2 (+https://github.com/tame-gg/conduit-native)";
  /** Where a superseded set is moved to, rather than deleted. */
  private static final String SUPERSEDED = "superseded";

  private ViaUpdater() {}

  /** What a check found, for the bootstrap to log. Never an exception. */
  public record Outcome(Kind kind, String detail) {
    public enum Kind { UP_TO_DATE, AVAILABLE, UPDATED, SKIPPED, FAILED }

    static Outcome of(Kind kind, String detail) {
      return new Outcome(kind, detail);
    }
  }

  /**
   * Checks for a newer Via and, unless {@code checkOnly}, downloads it into {@code viaDirectory}.
   *
   * @param viaDirectory {@code lib/via} beside the configuration, created only if there is something
   *     to put in it
   * @param have the versions already in effect: what is in {@code lib/via}, or the bundled versions
   *     where that directory has none
   */
  public static Outcome update(Path viaDirectory, Map<String, String> have, boolean checkOnly, int timeoutMs) {
    try {
      HttpClient client = HttpClient.newBuilder()
          .connectTimeout(Duration.ofMillis(timeoutMs))
          // A redirect to a mirror is normal for a Maven repository; one to somewhere else is not
          // followed, because NEVER would also refuse the ordinary case.
          .followRedirects(HttpClient.Redirect.NORMAL)
          .build();

      Map<String, String> latest = new LinkedHashMap<>();
      List<String> newer = new ArrayList<>();
      for (String artifact : ViaArtifacts.NAMES) {
        String current = have.get(artifact);
        String release = release(client, artifact, timeoutMs);
        if (release == null || !ViaArtifacts.isRelease(release)) {
          return Outcome.of(Outcome.Kind.FAILED, "no release version listed for " + artifact);
        }
        if (ViaArtifacts.major(release) != ViaArtifacts.major(current)) {
          // Reported, not taken: see the class comment.
          return Outcome.of(Outcome.Kind.SKIPPED, artifact + " " + release + " is a new major version (this"
              + " Conduit is built against " + current + "). Update Conduit, or put the jars in lib/via"
              + " yourself to try it.");
        }
        latest.put(artifact, release);
        if (ViaArtifacts.compare(release, current) > 0) newer.add(artifact + " " + current + " -> " + release);
      }
      if (newer.isEmpty()) return Outcome.of(Outcome.Kind.UP_TO_DATE, describe(latest));
      if (checkOnly) return Outcome.of(Outcome.Kind.AVAILABLE, String.join(", ", newer));

      // Everything to a staging directory first. A set half on disk is a class path with two versions
      // of the same artifact on it, which is worse than not updating at all.
      Path staging = viaDirectory.resolve(".incoming");
      deleteRecursively(staging);
      Files.createDirectories(staging);
      Map<String, Path> downloaded = new LinkedHashMap<>();
      try {
        for (Map.Entry<String, String> entry : latest.entrySet()) {
          downloaded.put(entry.getKey(), download(client, entry.getKey(), entry.getValue(), staging, timeoutMs));
        }
        supersede(viaDirectory);
        for (Path jar : downloaded.values()) {
          Files.move(jar, viaDirectory.resolve(jar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
      } finally {
        deleteRecursively(staging);
      }
      return Outcome.of(Outcome.Kind.UPDATED, String.join(", ", newer));
    } catch (IOException | InterruptedException | RuntimeException failure) {
      if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
      return Outcome.of(Outcome.Kind.FAILED, describe(failure));
    }
  }

  /**
   * Downloads one exact set into {@code viaDirectory}, for a proxy that has no ViaVersion at all.
   *
   * <p>ViaVersion is not inside the jar: it is GPL object code, and shipping it is what would oblige
   * every copy of Conduit to travel with Via's Corresponding Source. So a first start installs the
   * set this build is pinned to, and {@link #update} moves it forward from there.
   *
   * <p>The versions asked for are the pinned ones rather than the newest published, so a first start
   * and a hundredth start of the same build install the same thing, and a Via that has just changed
   * under a network is an update it can see in the log rather than a surprise on a fresh box.
   */
  public static Outcome install(Path viaDirectory, Map<String, String> want, int timeoutMs) {
    try {
      HttpClient client = HttpClient.newBuilder()
          .connectTimeout(Duration.ofMillis(timeoutMs))
          .followRedirects(HttpClient.Redirect.NORMAL)
          .build();
      Path staging = viaDirectory.resolve(".incoming");
      deleteRecursively(staging);
      Files.createDirectories(staging);
      List<Path> downloaded = new ArrayList<>();
      Map<String, String> installing = new LinkedHashMap<>();
      try {
        for (String artifact : ViaArtifacts.NAMES) {
          String pinned = want.get(artifact);
          if (pinned == null || pinned.isBlank()) {
            return Outcome.of(Outcome.Kind.FAILED, "no version pinned for " + artifact);
          }
          // The newest release in the pinned major, so a first start does not download the pinned set
          // and then immediately replace it. Anything else -- a repository that will not say, a new
          // major, a version that is not a release -- and the pinned one is what is installed.
          String version = pinned;
          try {
            String release = release(client, artifact, timeoutMs);
            if (release != null && ViaArtifacts.isRelease(release)
                && ViaArtifacts.major(release) == ViaArtifacts.major(pinned)
                && ViaArtifacts.compare(release, pinned) > 0) {
              version = release;
            }
          } catch (IOException | RuntimeException unavailable) {
            version = pinned;
          }
          installing.put(artifact, version);
          downloaded.add(download(client, artifact, version, staging, timeoutMs));
        }
        Files.createDirectories(viaDirectory);
        for (Path jar : downloaded) {
          Files.move(jar, viaDirectory.resolve(jar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
      } finally {
        deleteRecursively(staging);
      }
      return Outcome.of(Outcome.Kind.UPDATED, describe(installing));
    } catch (IOException | InterruptedException | RuntimeException failure) {
      if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
      return Outcome.of(Outcome.Kind.FAILED, describe(failure));
    }
  }

  /** The {@code <release>} in an artifact's Maven metadata, or null. */
  private static String release(HttpClient client, String artifact, int timeoutMs)
      throws IOException, InterruptedException {
    String xml = text(client, REPOSITORY + "/" + ViaArtifacts.metadataPath(artifact), timeoutMs);
    // One element out of a document written by the repository, not by a user. A parser would pull in
    // XML entity handling for a value that is a version number.
    int open = xml.indexOf("<release>");
    int close = xml.indexOf("</release>", open + 1);
    if (open < 0 || close < 0) return null;
    return xml.substring(open + "<release>".length(), close).strip();
  }

  /**
   * Downloads one jar into {@code staging}, verified against the SHA-256 the repository publishes
   * beside it.
   *
   * <p>The checksum is not a signature -- anyone who could replace the jar could replace it too -- so
   * it catches a truncated or corrupted download rather than a hostile one. What guards against the
   * latter is that the jar is fetched over HTTPS from ViaVersion's own repository.
   */
  private static Path download(HttpClient client, String artifact, String version, Path staging, int timeoutMs)
      throws IOException, InterruptedException {
    String path = ViaArtifacts.repositoryPath(artifact, version);
    String expected = text(client, REPOSITORY + "/" + path + ".sha256", timeoutMs).strip().toLowerCase(java.util.Locale.ROOT);
    // Some repositories append " filename" to a checksum file.
    int space = expected.indexOf(' ');
    if (space > 0) expected = expected.substring(0, space);

    Path target = staging.resolve(ViaArtifacts.fileName(artifact, version));
    HttpRequest request = HttpRequest.newBuilder(URI.create(REPOSITORY + "/" + path))
        .header("User-Agent", USER_AGENT)
        .timeout(Duration.ofMillis(Math.max(timeoutMs, 30_000)))
        .GET()
        .build();
    HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    if (response.statusCode() != 200) {
      throw new IOException("HTTP " + response.statusCode() + " for " + path);
    }
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IOException("SHA-256 unavailable", impossible);
    }
    try (InputStream body = response.body(); var output = Files.newOutputStream(target)) {
      byte[] buffer = new byte[16 * 1024];
      for (int read = body.read(buffer); read >= 0; read = body.read(buffer)) {
        digest.update(buffer, 0, read);
        output.write(buffer, 0, read);
      }
    }
    String actual = hex(digest.digest());
    if (!actual.equals(expected)) {
      Files.deleteIfExists(target);
      throw new IOException(ViaArtifacts.fileName(artifact, version) + " does not match its published SHA-256");
    }
    return target;
  }

  /**
   * Moves whatever Via jars are already in the directory into {@code superseded/}.
   *
   * <p>Moved rather than deleted: an update that turns out to misbehave is then one copy back, and an
   * updater that deletes jars an operator put there by hand is an updater nobody trusts. Only the top
   * level is a class path, so a set parked in a subdirectory is out of the way.
   */
  private static void supersede(Path viaDirectory) throws IOException {
    Map<String, ViaArtifacts.Found> existing = ViaArtifacts.inDirectory(viaDirectory);
    if (existing.isEmpty()) {
      Files.createDirectories(viaDirectory);
      return;
    }
    Path old = viaDirectory.resolve(SUPERSEDED);
    Files.createDirectories(old);
    try (var entries = Files.list(viaDirectory)) {
      for (Path file : entries.filter(Files::isRegularFile).toList()) {
        String name = file.getFileName().toString();
        if (!name.endsWith(".jar")) continue;
        boolean via = ViaArtifacts.NAMES.stream().anyMatch(artifact -> name.startsWith(artifact + "-"));
        if (via) Files.move(file, old.resolve(name), StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }

  private static String text(HttpClient client, String url, int timeoutMs) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
        .header("User-Agent", USER_AGENT)
        .timeout(Duration.ofMillis(timeoutMs))
        .GET()
        .build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + " for " + url);
    return response.body();
  }

  /**
   * A failure in a few words.
   *
   * <p>The common ones carry no message at all -- a refused connection is a bare
   * {@code ConnectException}, a timeout a bare {@code HttpConnectTimeoutException} -- and reporting
   * {@code null} told an operator nothing about whether their proxy, their DNS or the repository was
   * the problem. The class name is the useful half when there is no message.
   */
  private static String describe(Throwable failure) {
    String message = failure.getMessage();
    String type = failure.getClass().getSimpleName();
    return message == null || message.isBlank() ? type + " reaching " + REPOSITORY : type + ": " + message;
  }

  private static String describe(Map<String, String> versions) {
    List<String> parts = new ArrayList<>(versions.size());
    versions.forEach((artifact, version) -> parts.add(artifact + " " + version));
    return String.join(", ", parts);
  }

  private static String hex(byte[] bytes) {
    StringBuilder text = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) text.append(Character.forDigit((value >> 4) & 0xF, 16)).append(Character.forDigit(value & 0xF, 16));
    return text.toString();
  }

  private static void deleteRecursively(Path path) throws IOException {
    if (!Files.exists(path)) return;
    try (var entries = Files.walk(path)) {
      for (Path each : entries.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(each);
    }
  }
}
