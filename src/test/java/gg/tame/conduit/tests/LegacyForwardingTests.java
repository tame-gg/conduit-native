// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.config.ForwardingMode;
import gg.tame.conduit.forwarding.ForwardingSecret;
import gg.tame.conduit.forwarding.LegacyForwarder;
import gg.tame.conduit.login.PlayerProfile;
import gg.tame.conduit.login.ProfileProperty;
import gg.tame.conduit.network.MinecraftProxy;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** BungeeCord-style forwarding: what a Spigot or Paper backend with bungeecord: true splits out of the host. */
final class LegacyForwardingTests {
  private static final UUID ID = UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");
  private static final String ID_PLAIN = "0f1e2d3c4b5a69788796a5b4c3d2e1f0";

  public static void main(String[] args) throws Exception { run(); }

  static void run() throws Exception {
    offlinePlayerHasNoProperties();
    onlinePlayerCarriesSignedTextures();
    bungeeGuardAppendsTheToken();
    forgeMarkerMovesIntoExtraData();
    nulInAPropertyCannotSplitTheHost();
    proxyWritesTheForwardedHostToTheBackend();
    System.out.println("LegacyForwardingTests OK");
  }

  private static void offlinePlayerHasNoProperties() throws Exception {
    String host = LegacyForwarder.legacy().handshakeHost("play.example.com", player(List.of()), InetAddress.getByName("203.0.113.7"));
    require(host.equals("play.example.com\0" + "203.0.113.7\0" + ID_PLAIN), "offline host: " + printable(host));
  }

  private static void onlinePlayerCarriesSignedTextures() throws Exception {
    PlayerProfile online = player(List.of(new ProfileProperty("textures", "dGV4dHVyZXM=", Optional.of("c2lnbmVk"))));
    String host = LegacyForwarder.legacy().handshakeHost("play.example.com", online, InetAddress.getByName("203.0.113.7"));
    require(host.equals("play.example.com\0" + "203.0.113.7\0" + ID_PLAIN
        + "\0[{\"name\":\"textures\",\"value\":\"dGV4dHVyZXM=\",\"signature\":\"c2lnbmVk\"}]"), "online host: " + printable(host));
  }

  private static void bungeeGuardAppendsTheToken() throws Exception {
    Path secret = TempFiles.file("conduit-bungeeguard", ".secret");
    Files.writeString(secret, "token-abc\n");
    LegacyForwarder guard = LegacyForwarder.bungeeGuard(ForwardingSecret.load(secret));
    require(guard.mode() == ForwardingMode.BUNGEEGUARD, "bungeeguard mode");
    String bare = guard.handshakeHost("h", player(List.of()), InetAddress.getByName("10.0.0.2"));
    require(bare.equals("h\0" + "10.0.0.2\0" + ID_PLAIN + "\0[{\"name\":\"bungeeguard-token\",\"value\":\"token-abc\"}]"),
        "bungeeguard host, no properties: " + printable(bare));
    String textured = guard.handshakeHost("h", player(List.of(new ProfileProperty("textures", "v", Optional.of("s")))),
        InetAddress.getByName("10.0.0.2"));
    require(textured.endsWith("\0[{\"name\":\"textures\",\"value\":\"v\",\"signature\":\"s\"},"
        + "{\"name\":\"bungeeguard-token\",\"value\":\"token-abc\"}]"), "token goes after the player's properties: " + printable(textured));
  }

  /** Four NUL parts at most: the marker cannot stay on the host, so it rides in extraData, NULs as \1. */
  private static void forgeMarkerMovesIntoExtraData() throws Exception {
    String host = LegacyForwarder.legacy().handshakeHost("mods.example.com\0FML2\0", player(List.of()), InetAddress.getByName("203.0.113.7"));
    require(host.equals("mods.example.com\0" + "203.0.113.7\0" + ID_PLAIN
        + "\0[{\"name\":\"extraData\",\"value\":\"\\u0001FML2\\u0001\"}]"), "forge host: " + printable(host));
    require(host.split("\0").length == 4, "host still splits into four parts");
  }

  private static void nulInAPropertyCannotSplitTheHost() throws Exception {
    String host = LegacyForwarder.legacy().handshakeHost("h", player(List.of(new ProfileProperty("x", "a\0b\"", Optional.empty()))),
        InetAddress.getByName("10.0.0.2"));
    require(host.split("\0").length == 4 && host.endsWith("[{\"name\":\"x\",\"value\":\"a\\u0000b\\\"\"}]"), "escaped: " + printable(host));
  }

  /** End to end: the backend's handshake, as a mock backend reads it off the socket. */
  private static void proxyWritesTheForwardedHostToTheBackend() throws Exception {
    try (ServerSocket backendListener = new ServerSocket(0)) {
      byte[][] captured = new byte[1][];
      Thread backend = Thread.startVirtualThread(() -> {
        try {
          var accepted = AllTests.acceptLogin(backendListener);
          captured[0] = accepted.getValue();
          try (Socket socket = accepted.getKey()) {
            MinecraftFrames.read(socket.getInputStream(), 128);
            MinecraftFrames.write(socket.getOutputStream(), LOGIN_SUCCESS);
            try { while (true) MinecraftFrames.read(socket.getInputStream(), 2048); } catch (Exception closed) { }
          }
        } catch (Exception exception) { throw new RuntimeException(exception); }
      });
      int proxyPort;
      try (ServerSocket reserve = new ServerSocket(0)) { proxyPort = reserve.getLocalPort(); }
      ConduitConfiguration configuration = new ConduitConfiguration(new InetSocketAddress("127.0.0.1", proxyPort), 128,
          ForwardingMode.LEGACY, Optional.empty(),
          List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", backendListener.getLocalPort()))), List.of("lobby"), List.of());
      try (MinecraftProxy proxy = new MinecraftProxy(configuration)) {
        Thread serving = Thread.startVirtualThread(() -> { try { proxy.serve(); } catch (Exception ignored) { } });
        try (Socket client = new Socket("127.0.0.1", proxy.port())) {
          MinecraftFrames.write(client.getOutputStream(), new byte[] {0, (byte) 0xFD, 5, 5, 'l', 'o', 'c', 'a', 'l', 0x63, (byte) 0xDD, 2});
          MinecraftFrames.write(client.getOutputStream(), LOGIN_START);
          MinecraftFrames.read(client.getInputStream(), 128);
        }
        backend.join(10_000); serving.interrupt();
      }
      require(captured[0] != null, "backend saw no login handshake");
      Handshake seen = Handshake.decode(captured[0]);
      // Offline mode keeps the UUID the 1.20.4 Login Start claimed, here all zeros.
      require(seen.requestedHost().equals("local\0" + "127.0.0.1\0" + "0".repeat(32)), "backend host: " + printable(seen.requestedHost()));
      require(seen.protocolVersion() == 765 && seen.requestedPort() == 25565 && seen.nextState() == 2, "rest of the handshake kept");
    }
  }

  private static final byte[] LOGIN_START = {0, 5, 'p', 'l', 'a', 'y', 'r', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
  private static final byte[] LOGIN_SUCCESS = {2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 5, 'p', 'l', 'a', 'y', 'r', 0};

  private static PlayerProfile player(List<ProfileProperty> properties) {
    return new PlayerProfile(ID, "playr", properties, !properties.isEmpty());
  }
  private static String printable(String host) { return host.replace("\0", "\\0"); }
  private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
