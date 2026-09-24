// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.ops;

import gg.tame.conduit.Conduit;
import gg.tame.conduit.config.UpdateSettings;
import gg.tame.conduit.log.ConduitLog;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Says in the log when GitHub has a Conduit release newer than this one: on start, then once a day.
 * It downloads and installs nothing. Off with {@code updates.conduit = false}, or
 * {@code -Dconduit.update.check=false}; only the launcher starts it, so tests never reach the network.
 */
public final class ConduitUpdateCheck {
  public static final String LATEST = "https://api.github.com/repos/tame-gg/conduit-native/releases/latest";
  private static final Pattern TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");

  private ConduitUpdateCheck() {}

  public static void start(UpdateSettings settings) {
    if (!settings.conduit() || "false".equalsIgnoreCase(System.getProperty("conduit.update.check"))) return;
    // A platform thread: the request is blocking socket I/O (JDK-8334574).
    Thread.ofPlatform().daemon().name("conduit-update-check").start(() -> {
      while (true) {
        try {
          newer(LATEST, Conduit.VERSION, settings.timeoutMs()).ifPresent(tag -> ConduitLog.info("Conduit " + tag
              + " is out (this is " + Conduit.VERSION + "): https://github.com/tame-gg/conduit-native/releases/latest"));
        } catch (IOException | RuntimeException failed) {
          ConduitLog.debug("Conduit update check failed: " + failed);
        } catch (InterruptedException stopped) {
          return;
        }
        try { Thread.sleep(Duration.ofHours(24)); } catch (InterruptedException stopped) { return; }
      }
    });
  }

  /** The latest release's tag at {@code url}, when it is newer than {@code current}. */
  public static Optional<String> newer(String url, String current, int timeoutMs) throws IOException, InterruptedException {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs))
        .followRedirects(HttpClient.Redirect.NORMAL).build();
    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
        .header("User-Agent", "Conduit/" + Conduit.VERSION + " (+https://github.com/tame-gg/conduit-native)")
        .header("Accept", "application/vnd.github+json")
        .timeout(Duration.ofMillis(timeoutMs)).GET().build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + " from " + url);
    Matcher tag = TAG.matcher(response.body());
    if (!tag.find()) throw new IOException("no tag_name in the release from " + url);
    return compare(tag.group(1), current) > 0 ? Optional.of(tag.group(1)) : Optional.empty();
  }

  /**
   * Semantic versions, a leading v and any +build ignored; a release outranks its own pre-releases.
   * ponytail: pre-release labels compare as plain text (beta.10 < beta.9); /releases/latest never
   * returns a pre-release, so only a pre-release build of Conduit itself would see that.
   */
  public static int compare(String a, String b) {
    String[] left = strip(a).split("-", 2);
    String[] right = strip(b).split("-", 2);
    String[] l = left[0].split("\\.");
    String[] r = right[0].split("\\.");
    for (int i = 0; i < Math.max(l.length, r.length); i++) {
      int c = Long.compare(part(l, i), part(r, i));
      if (c != 0) return c;
    }
    if (left.length != right.length) return left.length < right.length ? 1 : -1;
    return left.length == 1 ? 0 : Integer.signum(left[1].compareTo(right[1]));
  }

  private static String strip(String version) {
    String v = version.strip();
    if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
    int build = v.indexOf('+');
    return build < 0 ? v : v.substring(0, build);
  }

  private static long part(String[] parts, int index) {
    if (index >= parts.length) return 0;
    try { return Long.parseLong(parts[index]); } catch (NumberFormatException notNumber) { return 0; }
  }
}
