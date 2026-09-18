// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.metrics;

import com.sun.net.httpserver.HttpServer;
import gg.tame.conduit.log.ConduitLog;
import gg.tame.conduit.protocol.TranslationSupport;
import gg.tame.conduit.runtime.ConduitRuntime;
import gg.tame.conduit.session.PlayerSession;
import gg.tame.conduit.session.TrackedPlayer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Conduit's counters and a few gauges read from the live proxy, in Prometheus' text format at
 * {@code GET /metrics}. Off unless {@code [metrics] prometheus-address} is set. It carries counts
 * and the operator's own server names only: no player names, addresses, tokens or paths. One
 * platform thread answers every scrape, so a flood of scrapes costs one thread and never a player's.
 */
public final class PrometheusEndpoint implements AutoCloseable {
  private final HttpServer server;

  private PrometheusEndpoint(HttpServer server) { this.server = server; }

  public static PrometheusEndpoint start(InetSocketAddress address, ConduitRuntime runtime) throws IOException {
    HttpServer server = HttpServer.create(address, 16);
    server.createContext("/metrics", exchange -> {
      try (exchange) {
        if (!"GET".equals(exchange.getRequestMethod()) && !"HEAD".equals(exchange.getRequestMethod())) {
          exchange.sendResponseHeaders(405, -1);
          return;
        }
        byte[] body = render(runtime).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
        boolean head = "HEAD".equals(exchange.getRequestMethod());
        exchange.sendResponseHeaders(200, head ? -1 : body.length);
        if (!head) try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
      } catch (RuntimeException failed) {
        ConduitLog.warn("metrics scrape failed: " + failed);
      }
    });
    server.start();
    ConduitLog.info("Serving metrics at http://" + address.getHostString() + ":" + server.getAddress().getPort() + "/metrics");
    return new PrometheusEndpoint(server);
  }

  public int port() { return server.getAddress().getPort(); }

  @Override public void close() { server.stop(0); }

  /** The whole exposition, read at the moment of the scrape. */
  public static String render(ConduitRuntime runtime) {
    ConduitMetrics metrics = ConduitMetrics.current();
    StringBuilder out = new StringBuilder(4096);

    Map<String, Long> byPath = new TreeMap<>();
    for (TranslationSupport path : TranslationSupport.values()) byPath.put(path.name().toLowerCase(Locale.ROOT), 0L);
    Map<String, Long> byServer = new TreeMap<>();
    for (TrackedPlayer player : runtime.playerManager().all()) {
      if (player instanceof PlayerSession session) byPath.merge(session.translationSupport().name().toLowerCase(Locale.ROOT), 1L, Long::sum);
      String server = player.currentBackend();
      if (server != null && !server.isEmpty()) byServer.merge(server, 1L, Long::sum);
    }
    family(out, "conduit_players", "gauge", "Players in the game, by whether their session is DIRECT or TRANSLATED to its backend.");
    byPath.forEach((path, count) -> sample(out, "conduit_players", "path", path, count));
    family(out, "conduit_players_on_server", "gauge", "Players on each backend.");
    for (var server : runtime.selector().registry().all()) sample(out, "conduit_players_on_server", "server", server.name(), byServer.getOrDefault(server.name(), 0L));
    gauge(out, "conduit_backend_connections", "Open connections to backends.", metrics.activeBackends());
    gauge(out, "conduit_plugins", "Enabled plugins, of every format.", runtime.plugins().plugins().size());
    gauge(out, "conduit_shutting_down", "1 while the proxy is shutting down.", runtime.shuttingDown() ? 1 : 0);
    gauge(out, "conduit_maintenance_active", "1 while maintenance mode turns players away.", runtime.isMaintenanceActive() ? 1 : 0);

    var health = runtime.selector().health();
    if (health != null) {
      family(out, "conduit_backend_up", "gauge", "1 when health checks find the backend routable (healthy and not draining).");
      for (var snapshot : health.all()) sample(out, "conduit_backend_up", "server", snapshot.name(), snapshot.routable() ? 1 : 0);
      family(out, "conduit_backend_draining", "gauge", "1 while the backend is drained of new players.");
      for (var snapshot : health.all()) sample(out, "conduit_backend_draining", "server", snapshot.name(), snapshot.draining() ? 1 : 0);
      family(out, "conduit_backend_ping_seconds", "gauge", "Round trip of the last successful health ping of the backend.");
      for (var snapshot : health.all()) {
        health.advertisement(snapshot.name()).ifPresent(ad -> sample(out, "conduit_backend_ping_seconds", "server", snapshot.name(), ad.latencyMillis() / 1000.0));
      }
    }

    counter(out, "conduit_connections_accepted_total", "Connections let past the throttle.", metrics.connectionsAccepted());
    counter(out, "conduit_connections_throttled_total", "Connections turned away by the throttle.", metrics.connectionsThrottled());
    counter(out, "conduit_malformed_connections_total", "Connections dropped for an idle, unreadable or malformed handshake.", metrics.malformedProtocols());
    counter(out, "conduit_bot_filter_strikes_total", "Strikes the bot filter recorded.", metrics.botFilterStrikes());
    counter(out, "conduit_bot_filter_blocks_total", "Sources the bot filter blocked.", metrics.botFilterBlocks());
    counter(out, "conduit_channel_guard_actions_total", "Plugin messages the channel guard acted on.", metrics.channelGuardActions());
    counter(out, "conduit_attack_mode_activations_total", "Times attack mode was switched on.", metrics.attackModeActivations());
    counter(out, "conduit_authentications_total", "Players verified with Mojang.", metrics.authentications());
    counter(out, "conduit_backend_connects_total", "Connections made to backends.", metrics.backendConnects());
    counter(out, "conduit_backend_connect_failures_total", "Backends that could not be reached when a player was sent to them.", metrics.backendConnectFailures());
    counter(out, "conduit_backend_connect_seconds_total", "Time spent making backend connections.", metrics.backendConnectSeconds());
    counter(out, "conduit_server_switches_total", "Completed server switches.", metrics.switches());
    counter(out, "conduit_server_switch_failures_total", "Server switches that failed.", metrics.failedSwitches());
    counter(out, "conduit_server_switch_seconds_total", "Time spent in completed server switches.", metrics.switchSeconds());
    counter(out, "conduit_fallbacks_total", "Players sent to a fallback after losing their backend.", metrics.fallbackEvents());
    counter(out, "conduit_backend_unhealthy_transitions_total", "Times a backend went from healthy to unhealthy.", metrics.unhealthyTransitions());
    counter(out, "conduit_decode_failures_total", "Packets Conduit could not decode.", metrics.decodeFailures());
    counter(out, "conduit_encode_failures_total", "Packets Conduit could not encode.", metrics.encodeFailures());
    counter(out, "conduit_translation_failures_total", "Packets Via could not translate between a client and a backend.", metrics.translationFailures());
    counter(out, "conduit_plugin_task_failures_total", "Plugin scheduler task runs that threw.", metrics.pluginTaskFailures());
    counter(out, "conduit_packets_received_total", "Packets read from clients and backends.", metrics.packetsIn());
    counter(out, "conduit_packets_sent_total", "Packets written to clients and backends.", metrics.packetsOut());
    counter(out, "conduit_received_bytes_total", "Packet bytes read from clients and backends.", metrics.bytesIn());
    counter(out, "conduit_sent_bytes_total", "Packet bytes written to clients and backends.", metrics.bytesOut());
    return out.toString();
  }

  private static void family(StringBuilder out, String name, String type, String help) {
    out.append("# HELP ").append(name).append(' ').append(help).append('\n');
    out.append("# TYPE ").append(name).append(' ').append(type).append('\n');
  }
  private static void gauge(StringBuilder out, String name, String help, double value) {
    family(out, name, "gauge", help);
    out.append(name).append(' ').append(number(value)).append('\n');
  }
  private static void counter(StringBuilder out, String name, String help, double value) {
    family(out, name, "counter", help);
    out.append(name).append(' ').append(number(value)).append('\n');
  }
  private static void sample(StringBuilder out, String name, String label, String value, double sample) {
    out.append(name).append('{').append(label).append("=\"").append(escape(value)).append("\"} ").append(number(sample)).append('\n');
  }
  /** Label values may hold backslashes, quotes and line breaks only escaped. */
  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
  }
  private static String number(double value) {
    return value == Math.rint(value) && Math.abs(value) < 1e15 ? Long.toString((long) value) : Double.toString(value);
  }
}
