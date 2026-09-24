// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import com.sun.net.httpserver.HttpServer;
import gg.tame.conduit.ops.Alerts;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/** An alert is one JSON POST to the webhook, and no webhook means no post and no waiting. */
public final class AlertsTests {
  public static void main(String[] arguments) throws Exception { run(); }

  public static void run() throws Exception {
    List<String> bodies = new CopyOnWriteArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/hook", exchange -> {
      try (InputStream in = exchange.getRequestBody()) { bodies.add(new String(in.readAllBytes(), StandardCharsets.UTF_8)); }
      exchange.sendResponseHeaders(204, -1);
      exchange.close();
    });
    server.start();
    try {
      Alerts.configure(Optional.of(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hook")));
      Alerts.send("Backend \"lobby\" is unhealthy");
      require(NativeApiTests.waitFor(() -> !bodies.isEmpty(), 10_000), "the webhook was posted to");
      String body = bodies.getFirst();
      require(body.contains("\"content\":\"Backend \\\"lobby\\\" is unhealthy\""), "content carries the message, quotes escaped: " + body);
      require(body.contains("\"text\":"), "and a text field for receivers that read that one");

      Alerts.configure(Optional.empty());
      Alerts.send("nobody listening");
      Thread.sleep(200);
      require(bodies.size() == 1, "an alert with no webhook set posts nothing");
    } finally {
      Alerts.configure(Optional.empty());
      server.stop(0);
    }
    System.out.println("AlertsTests OK");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
