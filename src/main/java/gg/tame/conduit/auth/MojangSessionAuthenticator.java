// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.auth;

import gg.tame.conduit.config.AuthenticationMode;
import gg.tame.conduit.config.AuthenticationSettings;
import gg.tame.conduit.login.PlayerProfile;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class MojangSessionAuthenticator implements PlayerAuthenticator {
  private final AuthenticationSettings settings;
  private final HttpClient http;
  public MojangSessionAuthenticator(AuthenticationSettings settings) {
    this.settings = settings;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(settings.timeoutMillis())).build();
  }
  MojangSessionAuthenticator(AuthenticationSettings settings, HttpClient http) {
    this.settings = settings; this.http = http;
  }
  @Override public AuthenticationMode mode() { return AuthenticationMode.ONLINE; }
  @Override public PlayerProfile verify(SessionQuery query) throws AuthenticationException {
    try {
      String username = URLEncoder.encode(query.username(), StandardCharsets.UTF_8);
      String hash = URLEncoder.encode(query.serverHash(), StandardCharsets.UTF_8);
      String uri = settings.sessionUrl() + "?username=" + username + "&serverId=" + hash;
      HttpRequest request = HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofMillis(settings.timeoutMillis())).GET().build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 204 || response.statusCode() == 404) throw new AuthenticationException("session not found");
      if (response.statusCode() != 200) throw new AuthenticationException("authentication service unavailable");
      HasJoinedResponse.Result result = HasJoinedResponse.parse(response.body());
      return new PlayerProfile(result.uniqueId(), result.username(), result.properties(), true);
    } catch (AuthenticationException exception) { throw exception; }
    catch (java.net.http.HttpTimeoutException exception) { throw new AuthenticationException("authentication timed out", exception); }
    catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AuthenticationException("authentication interrupted", exception); }
    catch (Exception exception) { throw new AuthenticationException("authentication request failed", exception); }
  }
}
