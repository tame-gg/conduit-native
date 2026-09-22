// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.auth;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The account a Minecraft name belongs to, asked of Mojang's public profile lookup
 * ({@code GET https://api.mojang.com/users/profiles/minecraft/<name>}): a known name answers 200 with
 * {@code {"id": "<32 hex>", "name": "<name as registered>"}}, an unknown one answers 204 or 404.
 *
 * <p>{@code -Dconduit.profile-lookup-url} replaces the address up to the name, for a network whose
 * authentication goes through a mirror, and for tests.
 */
public final class MojangProfiles {
  private static final String DEFAULT_URL = "https://api.mojang.com/users/profiles/minecraft/";
  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  /** What a Minecraft name may be made of. Anything else is refused without asking anyone. */
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
  private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-fA-F]{32})\"");
  private static final Pattern REGISTERED = Pattern.compile("\"name\"\\s*:\\s*\"([A-Za-z0-9_]{1,16})\"");

  private MojangProfiles() {}

  /** What a lookup found. */
  public sealed interface Result {
    /** A real account: its UUID and the name as Mojang has it. */
    record Found(UUID account, String name) implements Result { }
    /** No account has this name, or it is not a name at all. */
    record NotFound() implements Result { }
    /** Mojang could not be asked, or answered with something else. */
    record Unavailable(String why) implements Result { }
  }

  public static boolean validName(String name) { return name != null && NAME.matcher(name).matches(); }

  /** Asks Mojang who {@code name} is. Blocks for up to five seconds; call it off any thread that matters. */
  public static Result lookup(String name) {
    if (!validName(name)) return new Result.NotFound();
    String base = System.getProperty("conduit.profile-lookup-url", DEFAULT_URL);
    try {
      HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
      HttpRequest request = HttpRequest.newBuilder(URI.create(base + URLEncoder.encode(name, StandardCharsets.UTF_8)))
          .timeout(TIMEOUT).header("Accept", "application/json").GET().build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() == 204 || response.statusCode() == 404) return new Result.NotFound();
      if (response.statusCode() != 200) return new Result.Unavailable("Mojang answered " + response.statusCode());
      Matcher id = ID.matcher(response.body());
      if (!id.find()) return new Result.Unavailable("Mojang's answer named no account");
      Matcher registered = REGISTERED.matcher(response.body());
      String hex = id.group(1);
      UUID account = UUID.fromString(hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
          + "-" + hex.substring(16, 20) + "-" + hex.substring(20));
      return new Result.Found(account, registered.find() ? registered.group(1) : name);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return new Result.Unavailable("interrupted");
    } catch (Exception failed) {
      return new Result.Unavailable(failed.getClass().getSimpleName() + (failed.getMessage() == null ? "" : ": " + failed.getMessage()));
    }
  }
}
