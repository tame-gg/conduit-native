// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.VelocityCompatTests.require;

import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.auth.Authenticators;
import gg.tame.conduit.config.AuthenticationSettings;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.config.ForwardingMode;
import gg.tame.conduit.config.HealthSettings;
import gg.tame.conduit.config.OpsSettings;
import gg.tame.conduit.config.StatusSettings;
import gg.tame.conduit.crypto.RsaKeys;
import gg.tame.conduit.network.MinecraftProxy;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What the server list is told is the proxy's, never a backend's.
 *
 * <p>A backend answers its own status ping with an icon, a MOTD, a version, counts and a sample of
 * its own, and the health probe reads that answer on a timer. None of it belongs in the answer a
 * player's client gets: the icon a server list shows is the one the operator put beside
 * conduit.toml, and with none configured the list shows the default silhouette rather than whatever
 * some backend happens to advertise.
 */
public final class StatusIsolationTests {
  public static void main(String[] arguments) throws Exception { run(); }

  static final String BACKEND_ICON = "data:image/png;base64,QkFDS0VORA==";
  private static final String PROXY_ICON = "data:image/png;base64,iVBORw0KGgo=";
  /** Everything a backend can advertise, none of it what the proxy is configured with. */
  private static final String BACKEND_STATUS = "{\"version\":{\"name\":\"Paper 1.21.4\",\"protocol\":769},"
      + "\"players\":{\"max\":1234,\"online\":999,\"sample\":[{\"name\":\"BackendPlayer\",\"id\":\"0f8fad5b-d9cb-469f-a165-70867728950e\"}]},"
      + "\"description\":{\"text\":\"backend motd\"},"
      + "\"favicon\":\"" + BACKEND_ICON + "\"}";

  public static void run() throws Exception {
    aBackendsIconNeverReachesTheServerList();
    theProxysOwnIconIsTheOnlyOneSent();
    System.out.println("StatusIsolationTests OK");
  }

  /** With no icon of its own, the proxy sends no icon at all -- not the one the backend answered with. */
  private static void aBackendsIconNeverReachesTheServerList() throws Exception {
    try (StatusBackend backend = new StatusBackend(BACKEND_STATUS);
         Proxy proxy = new Proxy(backend, new StatusSettings(Text.of("conduit motd"), 77, Optional.empty()))) {
      // Probed first, so the backend's whole answer is cached and in reach before the client asks.
      require(backend.await(1), "the health probe pings the backend");
      String json = proxy.ping();
      require(!json.contains("favicon"), "no icon where the proxy has none, got " + json);
      require(json.contains("conduit motd") && !json.contains("backend motd"), "the proxy's MOTD, got " + json);
      require(!json.contains("Paper 1.21.4"), "the proxy's version name, got " + json);
      require(!json.contains("1234") && !json.contains("999"), "the proxy's counts, got " + json);
      require(!json.contains("BackendPlayer"), "the proxy's player sample, got " + json);
    }
  }

  private static void theProxysOwnIconIsTheOnlyOneSent() throws Exception {
    try (StatusBackend backend = new StatusBackend(BACKEND_STATUS);
         Proxy proxy = new Proxy(backend, new StatusSettings(Text.of("conduit motd"), 77, Optional.of(PROXY_ICON)))) {
      require(backend.await(1), "the health probe pings the backend");
      String json = proxy.ping();
      require(json.contains("\"favicon\":\"" + PROXY_ICON + "\""), "the configured icon, got " + json);
      require(!json.contains(BACKEND_ICON), "and only that one, got " + json);
    }
  }

  /** The proxy under test, health probing its backend as a running one does. */
  private static final class Proxy implements AutoCloseable {
    private final MinecraftProxy proxy;
    private final Thread serving;
    Proxy(StatusBackend backend, StatusSettings status) throws Exception {
      OpsSettings ops = new OpsSettings(OpsSettings.CURRENT_SCHEMA, null, new HealthSettings(true, 500, 1_000, 3, 2),
          null, null, null, null, null, status);
      int port;
      try (ServerSocket probe = new ServerSocket(0)) { port = probe.getLocalPort(); }
      List<BackendServer> backends = List.of(new BackendServer("lobby", backend.address()));
      ConduitConfiguration configuration = new ConduitConfiguration(new InetSocketAddress("127.0.0.1", port), 1 << 16,
          ForwardingMode.NONE, Optional.empty(), backends, List.of("lobby"), List.of("lobby"),
          AuthenticationSettings.offline(), Optional.empty(), ops);
      Path root = TempFiles.dir("status-isolation");
      proxy = new MinecraftProxy(configuration, Authenticators.create(configuration.authentication()), RsaKeys.generate(),
          root.resolve("plugins"));
      serving = Thread.ofPlatform().daemon().name("status-isolation-serve").start(() -> {
        try { proxy.serve(); } catch (Exception ignored) { }
      });
    }
    /** The status JSON a vanilla client is sent. */
    String ping() throws IOException {
      try (Socket socket = new Socket("127.0.0.1", proxy.port())) {
        socket.setSoTimeout(10_000);
        MinecraftFrames.write(socket.getOutputStream(), new Handshake(47, "127.0.0.1", proxy.port(), 1).encode());
        MinecraftFrames.write(socket.getOutputStream(), new byte[] {0});
        byte[] response = MinecraftFrames.read(socket.getInputStream(), 1 << 20);
        return MinecraftInput.string(new DataInputStream(new ByteArrayInputStream(
            Arrays.copyOfRange(response, 1, response.length))), 1 << 16);
      }
    }
    @Override public void close() throws Exception {
      proxy.close();
      serving.join(10_000);
    }
  }

  /** A backend that answers a status ping with everything it has, as a real one does. */
  static final class StatusBackend implements AutoCloseable {
    private final ServerSocket listener = new ServerSocket(0);
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private final AtomicInteger answered = new AtomicInteger();
    StatusBackend(String status) throws IOException {
      Thread.ofPlatform().daemon().name("status-backend").start(() -> {
        while (!listener.isClosed()) {
          Socket socket;
          try { socket = listener.accept(); } catch (IOException closed) { return; }
          sockets.add(socket);
          Thread.ofPlatform().daemon().name("status-backend-session").start(() -> {
            try (socket) {
              Handshake.decode(MinecraftFrames.read(socket.getInputStream(), 4096));
              MinecraftFrames.read(socket.getInputStream(), 16);
              ByteArrayOutputStream bytes = new ByteArrayOutputStream();
              try (DataOutputStream output = new DataOutputStream(bytes)) {
                MinecraftOutput.varInt(output, 0);
                MinecraftOutput.string(output, status);
              }
              MinecraftFrames.write(socket.getOutputStream(), bytes.toByteArray());
              answered.incrementAndGet();
              socket.getInputStream().read();
            } catch (Exception ended) { }
          });
        }
      });
    }
    InetSocketAddress address() { return new InetSocketAddress("127.0.0.1", listener.getLocalPort()); }
    boolean await(int pings) throws InterruptedException {
      return NativeApiTests.waitFor(() -> answered.get() >= pings, 10_000);
    }
    @Override public void close() throws IOException {
      listener.close();
      for (Socket socket : sockets) socket.close();
    }
  }
}
