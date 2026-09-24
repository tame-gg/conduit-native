// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.ops;

import gg.tame.conduit.log.ConduitLog;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The things an operator wants told about while they are not watching the console, posted to one
 * webhook: a backend going unhealthy or recovering, attack mode engaged or lifted, the proxy up.
 *
 * <p>The body is JSON with both a {@code content} field, which Discord reads, and a {@code text}
 * field, which Slack and most generic receivers read, so one URL works without a format setting.
 * Every post is made on its own daemon thread and never waited for: an alert must not be able to
 * slow down the thing it is reporting on. A failure is one line at debug, since an unreachable
 * webhook during an outage is the usual case, not a fault worth a warning per event.
 */
public final class Alerts {
  private static volatile Optional<URI> webhook = Optional.empty();
  private static final ExecutorService POSTS = Executors.newSingleThreadExecutor(r -> {
    Thread thread = new Thread(r, "conduit-alerts");
    thread.setDaemon(true);
    return thread;
  });
  private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private Alerts() {}

  /** Where alerts go from now on; empty turns them off. Read on every alert, so a reload is enough. */
  public static void configure(Optional<URI> url) { webhook = url == null ? Optional.empty() : url; }

  public static boolean enabled() { return webhook.isPresent(); }

  /**
   * Posts {@code message}. Returns at once; the future says how it went, in a sentence, once it has
   * ("delivered (HTTP 204)", or what went wrong). A failure is also one warning in the log: a webhook
   * that never fires is a fault the operator should hear about, and it used to be said at debug only.
   */
  public static java.util.concurrent.CompletableFuture<String> send(String message) {
    Optional<URI> target = webhook;
    if (target.isEmpty()) return java.util.concurrent.CompletableFuture.completedFuture("no alerts.webhook-url is set");
    String body = "{\"content\":" + json(message) + ",\"text\":" + json(message) + "}";
    var outcome = new java.util.concurrent.CompletableFuture<String>();
    POSTS.execute(() -> {
      try {
        HttpRequest request = HttpRequest.newBuilder(target.get())
            .header("Content-Type", "application/json")
            .header("User-Agent", "Conduit")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 == 2) { outcome.complete("delivered (HTTP " + response.statusCode() + ")"); return; }
        String reply = response.body() == null ? "" : response.body().strip();
        String said = "webhook answered HTTP " + response.statusCode() + (reply.isEmpty() ? "" : ": " + (reply.length() > 200 ? reply.substring(0, 200) + "..." : reply));
        ConduitLog.warn("Alert not delivered: " + said);
        outcome.complete(said);
      } catch (Exception failed) {
        if (failed instanceof InterruptedException) Thread.currentThread().interrupt();
        String said = "webhook not reached: " + failed.getClass().getSimpleName() + (failed.getMessage() == null ? "" : " " + failed.getMessage());
        ConduitLog.warn("Alert not delivered: " + said);
        outcome.complete(said);
      }
    });
    return outcome;
  }

  private static String json(String text) {
    StringBuilder out = new StringBuilder(text.length() + 2).append('"');
    for (char c : text.toCharArray()) {
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> { if (c < 0x20) out.append(String.format("\\u%04x", (int) c)); else out.append(c); }
      }
    }
    return out.append('"').toString();
  }
}
