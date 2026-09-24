// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.NativeApiTests.packet;
import static gg.tame.conduit.tests.NativeApiTests.reservePort;
import static gg.tame.conduit.tests.NativeApiTests.waitFor;

import gg.tame.conduit.auth.Authenticators;
import gg.tame.conduit.auth.PlayerAuthenticator;
import gg.tame.conduit.config.AuthenticationMode;
import gg.tame.conduit.config.AuthenticationSettings;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.config.ForwardingMode;
import gg.tame.conduit.config.HealthSettings;
import gg.tame.conduit.config.OpsSettings;
import gg.tame.conduit.config.TranslationSettings;
import gg.tame.conduit.crypto.AesCfb8;
import gg.tame.conduit.crypto.CipherStreams;
import gg.tame.conduit.crypto.RsaKeys;
import gg.tame.conduit.login.EncryptionRequest;
import gg.tame.conduit.network.MinecraftProxy;
import gg.tame.conduit.network.PacketTransport;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.TranslationSupport;
import gg.tame.conduit.session.PlayerSession;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * A 26.3 client on 26.2 backends through Via: Join Game's online-mode flag.
 *
 * <p>A real 26.3 client showed no player heads in TAB through an online-mode Conduit, on a first join
 * and after a switch. The client draws faces only when Join Game says the server is online-mode, a
 * backend behind forwarding says it is not, and Conduit's rewrite of that flag ran only on the
 * DIRECT path. Here the backends write {@code onlineMode=false}; an authenticated player must see
 * true in each Join Game, an offline-mode player must still see false.
 */
public final class ViaJoinGameOnlineModeTests {
  private static final int CLIENT = 777;
  private static final int BACKEND = 776;
  private static final ProtocolDefinition C = ProtocolDefinition.forVersion(CLIENT);
  private static final ProtocolDefinition B = ProtocolDefinition.forVersion(BACKEND);

  private ViaJoinGameOnlineModeTests() {}

  public static void main(String[] args) throws Exception {
    run();
  }

  public static void run() throws Exception {
    AuthenticationSettings online = new AuthenticationSettings(AuthenticationMode.ONLINE, "http://127.0.0.1:1/unused", 1000);
    joinAndSwitch(online, new LoginFlowTests.CountingAuthenticator(), true);
    joinAndSwitch(AuthenticationSettings.offline(), null, false);
    System.out.println("ViaJoinGameOnlineModeTests passed.");
  }

  private static void joinAndSwitch(AuthenticationSettings auth, PlayerAuthenticator authenticator, boolean expected) throws Exception {
    String mode = expected ? "online" : "offline";
    try (Backend776 lobby = new Backend776("lobby"); Backend776 survival = new Backend776("survival")) {
      OpsSettings ops = new OpsSettings(OpsSettings.CURRENT_SCHEMA, null, new HealthSettings(false, 10_000, 1_500, 3, 2), null, null, null, null,
          new TranslationSettings(true, TranslationSettings.TranslationEngine.VIA_PREFERRED, true, true, false, "via"), null);
      ConduitConfiguration configuration = new ConduitConfiguration(new InetSocketAddress("127.0.0.1", reservePort()), 1 << 20,
          ForwardingMode.NONE, Optional.empty(), List.of(lobby.server(), survival.server()), List.of("lobby"), List.of("lobby"),
          auth, Optional.empty(), ops);
      try (MinecraftProxy proxy = new MinecraftProxy(configuration,
          authenticator != null ? authenticator : Authenticators.create(auth), RsaKeys.generate(),
          TempFiles.dir("via-join-online-mode").resolve("plugins"))) {
        Thread.ofPlatform().daemon().name("via-online-mode-serve").start(() -> { try { proxy.serve(); } catch (Exception ignored) { } });
        try (Client777 client = Client777.join(proxy.port(), "Headed", expected)) {
          byte[] first = client.awaitJoinGame(1);
          PlayerSession player = (PlayerSession) proxy.runtime().player("Headed").orElseThrow();
          require(player.translationSupport() == TranslationSupport.TRANSLATED && player.backendProtocol() == BACKEND,
              "the session is Via-translated to 26.2: " + player.translationSupport() + " " + player.backendProtocol());
          require(player.authenticated() == expected, mode + ": authenticated is " + player.authenticated());
          requireFlags(first, expected, mode + " join");

          require(player.connectWithResult(proxy.runtime().servers().getServer("survival").orElseThrow())
              .get(15, TimeUnit.SECONDS).successful(), mode + ": switched to survival");
          requireFlags(client.awaitJoinGame(2), expected, mode + " switch");
        }
      }
    }
  }

  /** The backend wrote onlineMode=false, enforcesSecureChat=true; only the first may change. */
  private static void requireFlags(byte[] join, boolean onlineMode, String what) {
    require(join[join.length - 2] == (onlineMode ? 1 : 0), what + ": onlineMode should be " + onlineMode + ", got " + join[join.length - 2]);
    require(join[join.length - 1] == 1, what + ": enforcesSecureChat untouched");
  }

  /**
   * A 26.3 client: logs in, encrypting when asked, and keeps up with the configuration phases a join
   * and a switch put it through. Keeps every Join Game it is sent.
   */
  static final class Client777 implements AutoCloseable {
    private final Socket socket;
    private final PacketTransport transport;
    private final List<byte[]> joins = Collections.synchronizedList(new ArrayList<>());
    private volatile ConnectionState state = ConnectionState.LOGIN;

    private Client777(Socket socket, PacketTransport transport) {
      this.socket = socket;
      this.transport = transport;
    }

    static Client777 join(int port, String name, boolean encrypt) throws Exception {
      Socket socket = new Socket("127.0.0.1", port);
      socket.setSoTimeout(20_000);
      MinecraftFrames.write(socket.getOutputStream(), new Handshake(CLIENT, "localhost", 25565, 2).encode());
      MinecraftFrames.write(socket.getOutputStream(), packet(C.id(ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, PacketKind.LOGIN_START), output -> {
        MinecraftOutput.string(output, name);
        UUID offline = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        output.writeLong(offline.getMostSignificantBits()); output.writeLong(offline.getLeastSignificantBits());
      }));
      PacketTransport transport = encrypt ? encrypt(socket) : new PacketTransport(socket.getInputStream(), socket.getOutputStream());
      Client777 client = new Client777(socket, transport);
      Thread.ofPlatform().daemon().name("via-online-mode-client").start(client::read);
      return client;
    }

    /** The encryption exchange, as LoginLifecycleTests.encrypt does it for 1.8, in 26.3's layout. */
    private static PacketTransport encrypt(Socket socket) throws Exception {
      EncryptionRequest request = EncryptionRequest.decode(C, MinecraftFrames.read(socket.getInputStream(), 4096));
      byte[] shared = new byte[16];
      new SecureRandom().nextBytes(shared);
      var key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(request.publicKey()));
      MinecraftFrames.write(socket.getOutputStream(), packet(C.id(ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, PacketKind.LOGIN_ENCRYPTION_RESPONSE), output -> {
        MinecraftOutput.bytes(output, RsaKeys.encrypt(key, shared));
        MinecraftOutput.bytes(output, RsaKeys.encrypt(key, request.verifyToken()));
      }));
      return new PacketTransport(CipherStreams.decrypting(socket.getInputStream(), AesCfb8.decryptor(shared)),
          CipherStreams.encrypting(socket.getOutputStream(), AesCfb8.encryptor(shared)));
    }

    private void read() {
      try {
        while (true) {
          byte[] frame = transport.read(1 << 21);
          ConnectionState at = state;
          int id = PlayPackets.packetId(frame);
          if (at == ConnectionState.LOGIN && C.is(at, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.LOGIN_SUCCESS)) {
            state = ConnectionState.CONFIGURATION;
            send(PlayPackets.loginAcknowledged(C));
          } else if (at == ConnectionState.CONFIGURATION && C.is(at, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.CONFIGURATION_FINISH)) {
            state = ConnectionState.PLAY;
            send(new byte[] {(byte) C.id(at, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_FINISH)});
          } else if (at == ConnectionState.PLAY && C.is(at, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.PLAY_START_CONFIGURATION)) {
            state = ConnectionState.CONFIGURATION;
            send(new byte[] {(byte) C.id(at, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CONFIGURATION_ACKNOWLEDGED)});
          } else if (at == ConnectionState.PLAY && C.is(at, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.PLAY_LOGIN)) {
            joins.add(frame);
          }
        }
      } catch (IOException closed) { }
    }

    private synchronized void send(byte[] packet) throws IOException { transport.write(packet); }

    /** The {@code count}th Join Game, waited for. */
    byte[] awaitJoinGame(int count) throws InterruptedException {
      require(waitFor(() -> joins.size() >= count, 15_000), "Join Game #" + count + " never arrived; the client is in " + state);
      return joins.get(count - 1);
    }

    @Override public void close() throws IOException { socket.close(); }
  }

  /**
   * A 26.2 server behind forwarding: answers the proxy's status probe as 776, logs the player in,
   * configures them at once and sends a Join Game that says it is not online-mode.
   */
  static final class Backend776 implements AutoCloseable {
    private final String name;
    private final ServerSocket listener = new ServerSocket(0);
    private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<>());

    Backend776(String name) throws IOException {
      this.name = name;
      Thread.ofPlatform().daemon().name("via-online-mode-" + name).start(() -> {
        try {
          while (true) {
            Socket socket = listener.accept();
            sockets.add(socket);
            Thread.ofPlatform().daemon().start(() -> serve(socket));
          }
        } catch (IOException closed) { }
      });
    }

    BackendServer server() { return new BackendServer(name, new InetSocketAddress("127.0.0.1", listener.getLocalPort())); }

    private void serve(Socket socket) {
      try (socket) {
        InputStream in = socket.getInputStream();
        if (Handshake.decode(MinecraftFrames.read(in, 4096)).nextState() != 2) {
          MinecraftFrames.read(in, 4096);
          write(socket, packet(0, output -> MinecraftOutput.string(output,
              "{\"version\":{\"name\":\"26.2\",\"protocol\":" + BACKEND + "},\"players\":{\"max\":20,\"online\":0},\"description\":{\"text\":\"\"}}")));
          return;
        }
        DataInputStream start = new DataInputStream(new ByteArrayInputStream(MinecraftFrames.read(in, 4096)));
        MinecraftInput.varInt(start);
        String player = MinecraftInput.string(start, 16);
        UUID uuid = new UUID(start.readLong(), start.readLong());
        write(socket, packet(B.id(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_SUCCESS), output -> {
          output.writeLong(uuid.getMostSignificantBits()); output.writeLong(uuid.getLeastSignificantBits());
          MinecraftOutput.string(output, player); MinecraftOutput.varInt(output, 0);
        }));
        readUntil(in, B.id(ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, PacketKind.LOGIN_ACKNOWLEDGED));
        // Via rewrites Join Game from the dimension types it saw; a vanilla-known entry, data left to the pack.
        write(socket, packet(B.id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_REGISTRY), output -> {
          MinecraftOutput.string(output, "minecraft:dimension_type");
          MinecraftOutput.varInt(output, 1);
          MinecraftOutput.string(output, "minecraft:overworld");
          output.writeBoolean(false);
        }));
        write(socket, new byte[] {(byte) B.id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_FINISH)});
        readUntil(in, B.id(ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_FINISH));
        socket.setSoTimeout(0);
        write(socket, joinGame());
        while (true) MinecraftFrames.read(in, 1 << 20);
      } catch (Exception ended) { }
    }

    /** 26.2 Join Game in one overworld, ending onlineMode=false, enforcesSecureChat=true. */
    private static byte[] joinGame() throws IOException {
      return packet(B.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN), output -> {
        output.writeInt(7); output.writeBoolean(false);
        MinecraftOutput.varInt(output, 1); MinecraftOutput.string(output, "minecraft:overworld");
        MinecraftOutput.varInt(output, 20); MinecraftOutput.varInt(output, 10); MinecraftOutput.varInt(output, 10);
        output.writeBoolean(false); output.writeBoolean(true); output.writeBoolean(false);
        MinecraftOutput.varInt(output, 0); MinecraftOutput.string(output, "minecraft:overworld");
        output.writeLong(0L); output.writeByte(0); output.writeByte(-1);
        output.writeBoolean(false); output.writeBoolean(false); output.writeBoolean(false);
        MinecraftOutput.varInt(output, 0); MinecraftOutput.varInt(output, 63);
        output.writeBoolean(false);   // onlineMode, as a backend behind forwarding says
        output.writeBoolean(true);    // enforcesSecureChat
      });
    }

    private static void readUntil(InputStream in, int wanted) throws IOException {
      while (MinecraftFrames.read(in, 1 << 20)[0] != wanted) { }
    }

    private static void write(Socket socket, byte[] packet) throws IOException {
      synchronized (socket) { MinecraftFrames.write(socket.getOutputStream(), packet); }
    }

    @Override public void close() throws IOException {
      listener.close();
      synchronized (sockets) { for (Socket socket : sockets) socket.close(); }
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
