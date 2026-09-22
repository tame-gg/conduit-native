// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.NativeApiTests.require;

import com.sun.net.httpserver.HttpServer;
import gg.tame.conduit.auth.MojangProfiles;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The name lookup {@code /gban} asks of Mojang before it bans someone offline, against a local
 * stand-in for {@code api.mojang.com} answering as Mojang documents: 200 with the account, 204 for a
 * name nobody has.
 */
public final class MojangProfilesTests {
  public static void main(String[] arguments) throws Exception { run(); }

  public static void run() throws Exception {
    theLookupReadsMojangsAnswers();
    System.out.println("MojangProfilesTests OK");
  }

  private static void theLookupReadsMojangsAnswers() throws Exception {
    AtomicInteger asked = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/users/profiles/minecraft/", exchange -> {
      asked.incrementAndGet();
      String name = exchange.getRequestURI().getPath().substring("/users/profiles/minecraft/".length());
      switch (name.toLowerCase(java.util.Locale.ROOT)) {
        case "griefer" -> {
          byte[] body = "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Griefer\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
        }
        case "broken" -> exchange.sendResponseHeaders(500, -1);
        default -> exchange.sendResponseHeaders(204, -1);
      }
      exchange.close();
    });
    server.start();
    String previous = System.getProperty("conduit.profile-lookup-url");
    System.setProperty("conduit.profile-lookup-url", "http://127.0.0.1:" + server.getAddress().getPort() + "/users/profiles/minecraft/");
    try {
      var found = MojangProfiles.lookup("griefer");
      require(found instanceof MojangProfiles.Result.Found hit
              && hit.account().equals(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")) && hit.name().equals("Griefer"),
          "a real name gives its account and its registered spelling, got " + found);
      require(MojangProfiles.lookup("NoSuchPlayer") instanceof MojangProfiles.Result.NotFound, "a name nobody has is not found");
      require(MojangProfiles.lookup("broken") instanceof MojangProfiles.Result.Unavailable, "an error answer is not taken as either");
      int before = asked.get();
      require(MojangProfiles.lookup("not a name!") instanceof MojangProfiles.Result.NotFound
          && MojangProfiles.lookup("waytoolongforaminecraftname") instanceof MojangProfiles.Result.NotFound,
          "something that cannot be a name is refused");
      require(asked.get() == before, "without asking Mojang");
    } finally {
      if (previous == null) System.clearProperty("conduit.profile-lookup-url");
      else System.setProperty("conduit.profile-lookup-url", previous);
      server.stop(0);
    }
    System.setProperty("conduit.profile-lookup-url", "http://127.0.0.1:1/");
    try {
      require(MojangProfiles.lookup("Anyone") instanceof MojangProfiles.Result.Unavailable, "an unreachable Mojang is unavailable, not a missing name");
    } finally {
      System.clearProperty("conduit.profile-lookup-url");
    }
  }
}
