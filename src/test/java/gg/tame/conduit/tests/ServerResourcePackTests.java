// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.NativeApiTests.require;
import static gg.tame.conduit.tests.NativeApiTests.waitFor;
import static gg.tame.conduit.tests.ResourcePackTests.bytes;
import static gg.tame.conduit.tests.ResourcePackTests.readUntil;
import static gg.tame.conduit.tests.ResourcePackTests.status18;
import static gg.tame.conduit.tests.VelocityCompatTests.awaitSignal;

import gg.tame.conduit.api.player.ResourcePack;
import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.auth.Authenticators;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.crypto.RsaKeys;
import gg.tame.conduit.network.MinecraftProxy;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.session.ClientResourcePacks;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A server's resource packs put to plugins on their way to the client: a compiled Velocity plugin
 * denies one pack, puts another in place of a second, lets a third through and keeps a removal from
 * the client, for a scripted 1.20.4 client in Configuration and in Play and a scripted 1.12.2 client
 * in Play, each against a scripted server of its own release. Every packet the client and the
 * server get is checked byte for byte. 1.8's answers, which carry the pack's hash, are checked on
 * the bookkeeping itself.
 */
public final class ServerResourcePackTests {
  public static void main(String[] arguments) throws Exception { run(); }

  static final UUID DENY = new UUID(1, 1);
  static final UUID SWAP = new UUID(2, 2);
  static final UUID KEEP = new UUID(3, 3);
  static final UUID REPLACEMENT = new UUID(7, 7);
  static final String REPLACEMENT_URL = "https://proxy.invalid/replacement.zip";
  static final String REPLACEMENT_HASH = "11".repeat(20);

  private static final String VSPACK = """
      package vspack;
      import com.velocitypowered.api.event.ResultedEvent;
      import com.velocitypowered.api.event.Subscribe;
      import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent;
      import com.velocitypowered.api.event.player.ServerResourcePackRemoveEvent;
      import com.velocitypowered.api.event.player.ServerResourcePackSendEvent;
      import com.velocitypowered.api.plugin.Plugin;
      import com.velocitypowered.api.proxy.ProxyServer;
      import java.util.UUID;
      import javax.inject.Inject;

      @Plugin(id = "vspack", name = "VSPack", version = "1.0")
      public final class Main {
        @SuppressWarnings("unchecked")
        static void signal(String value) { ((java.util.Queue<String>) System.getProperties().get("velocity.test.signals")).add(value); }
        private final ProxyServer proxy;
        @Inject public Main(ProxyServer proxy) { this.proxy = proxy; }

        @Subscribe public void send(ServerResourcePackSendEvent event) {
          String url = event.getReceivedResourcePack().getUrl();
          signal("send:" + event.getServerConnection().getServerInfo().getName() + ":" + url + ":" + event.getReceivedResourcePack().getOrigin());
          if (url.contains("deny")) event.setResult(ResultedEvent.GenericResult.denied());
          if (url.contains("swap")) {
            byte[] hash = new byte[20];
            java.util.Arrays.fill(hash, (byte) 0x11);
            event.setProvidedResourcePack(proxy.createResourcePackBuilder("https://proxy.invalid/replacement.zip")
                .setId(new UUID(7, 7)).setHash(hash).setShouldForce(true).build());
          }
        }
        @Subscribe public void remove(ServerResourcePackRemoveEvent event) {
          signal("remove:" + event.getPackId());
          if (new UUID(3, 3).equals(event.getPackId())) event.setResult(ResultedEvent.GenericResult.denied());
        }
        @Subscribe public void status(PlayerResourcePackStatusEvent event) {
          signal("status:" + event.getPackId() + ":" + event.getStatus() + ":" + event.getPackInfo().getOrigin());
        }
      }
      """;

  public static void run() throws Exception {
    legacyHashAnswersAreAboutTheServersPack();
    Path root = TempFiles.dir("server-resource-packs");
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    VelocityCompatTests.jar(VelocityCompatTests.compile(root, "vspack.Main", VSPACK, List.of(), true), plugins.resolve("vspack.jar"), null);
    aModernClientsServerPacks(plugins);
    anOlderClientsServerPacks(plugins);
    System.out.println("ServerResourcePackTests OK");
  }

  // ---------------------------------------------------------------- 1.20.4

  private static void aModernClientsServerPacks(Path plugins) throws Exception {
    VelocityCompatTests.installSignals();
    ProtocolDefinition p = ProtocolDefinition.forVersion(765);
    int configPush = p.id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_RESOURCE_PACK_PUSH);
    int configStatus = p.id(ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_RESOURCE_PACK_STATUS);
    int playPush = p.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_RESOURCE_PACK_PUSH);
    int playPop = p.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_RESOURCE_PACK_POP);
    int playStatus = p.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_RESOURCE_PACK_STATUS);
    byte finishOut = (byte) p.id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_FINISH);
    byte finishIn = (byte) p.id(ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_FINISH);
    byte joinGame = (byte) p.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN);
    byte[] pushDeny = push(configPush, DENY, "https://server.invalid/deny.zip", "aa".repeat(20));
    byte[] pushSwap = push(configPush, SWAP, "https://server.invalid/swap.zip", "bb".repeat(20));
    byte[] pushKeep = push(configPush, KEEP, "https://server.invalid/keep.zip", "cc".repeat(20));
    try (PackServer lobby = new PackServer(765, List.of(pushDeny, pushSwap, pushKeep))) {
      ConduitConfiguration configuration = VelocityCompatTests.configuration(List.of(lobby.server("lobby")));
      try (MinecraftProxy proxy = new MinecraftProxy(configuration, Authenticators.create(configuration.authentication()), RsaKeys.generate(), plugins)) {
        Thread.ofPlatform().daemon().start(() -> { try { proxy.serve(); } catch (Exception ignored) { } });
        try (Socket client = new Socket("127.0.0.1", proxy.port())) {
          client.setSoTimeout(15_000);
          InputStream in = client.getInputStream();
          OutputStream out = client.getOutputStream();
          MinecraftFrames.write(out, new Handshake(765, "localhost", 25565, 2).encode());
          MinecraftFrames.write(out, ModLoaderTests.loginStart());
          require(MinecraftFrames.read(in, 8192)[0] == 0x02, "Login Success");
          MinecraftFrames.write(out, new byte[] {0x03});

          // Configuration: the denied pack never reaches the client, the swapped one arrives as the replacement.
          List<byte[]> configuring = readUntil(in, packet -> packet.length == 1 && packet[0] == finishOut);
          List<byte[]> pushed = configuring.stream().filter(packet -> packet[0] == (byte) configPush).toList();
          require(pushed.size() == 2 && Arrays.equals(pushed.get(0), push(configPush, REPLACEMENT, REPLACEMENT_URL, REPLACEMENT_HASH, true))
              && Arrays.equals(pushed.get(1), pushKeep), "the client is pushed the replacement, then the kept pack as the server wrote it: " + describe(pushed));
          require(lobby.await(bytes(configStatus, out2 -> { uuid(out2, DENY); MinecraftOutput.varInt(out2, 1); })),
              "the server is told its denied pack was declined");
          for (int result : new int[] {3, 0}) {
            MinecraftFrames.write(out, status765(configStatus, REPLACEMENT, result));
            MinecraftFrames.write(out, status765(configStatus, KEEP, result));
            require(lobby.await(status765(configStatus, SWAP, result)), "the answer about the replacement reaches the server as about its own pack, " + result);
            require(lobby.await(status765(configStatus, KEEP, result)), "the answer about the kept pack reaches the server as it was, " + result);
          }
          MinecraftFrames.write(out, new byte[] {finishIn});
          readUntil(in, packet -> packet[0] == joinGame);

          // Play: removing the swapped pack removes the replacement; removing the kept one is denied; a denied offer is declined.
          lobby.send(pop(playPop, SWAP));
          lobby.send(pop(playPop, KEEP));
          lobby.send(push(playPush, DENY, "https://server.invalid/deny.zip", "aa".repeat(20)));
          byte[] popAll = pop(playPop, null);
          lobby.send(popAll);
          List<byte[]> playing = readUntil(in, packet -> Arrays.equals(packet, popAll));
          List<byte[]> packs = playing.stream().filter(packet -> packet[0] == (byte) playPop || packet[0] == (byte) playPush).toList();
          require(packs.size() == 2 && Arrays.equals(packs.get(0), pop(playPop, REPLACEMENT)), "the replacement is popped, the kept pack is not, "
              + "and the denied offer never arrives: " + describe(packs));
          require(lobby.await(bytes(playStatus, out2 -> { uuid(out2, DENY); MinecraftOutput.varInt(out2, 1); })),
              "the server is told its pack was declined, in Play too");
        }
        List<String> seen = signals().stream().filter(signal -> !signal.startsWith("status:")).toList();
        require(seen.equals(List.of(
            "send:lobby:https://server.invalid/deny.zip:DOWNSTREAM_SERVER", "send:lobby:https://server.invalid/swap.zip:DOWNSTREAM_SERVER",
            "send:lobby:https://server.invalid/keep.zip:DOWNSTREAM_SERVER",
            "remove:" + SWAP, "remove:" + KEEP, "send:lobby:https://server.invalid/deny.zip:DOWNSTREAM_SERVER", "remove:null")),
            "the plugin saw every offer and removal, as the server made them: " + seen);
        require(waitFor(() -> signals().containsAll(List.of("status:" + REPLACEMENT + ":ACCEPTED:DOWNSTREAM_SERVER",
            "status:" + REPLACEMENT + ":SUCCESSFUL:DOWNSTREAM_SERVER", "status:" + KEEP + ":SUCCESSFUL:DOWNSTREAM_SERVER")), 5_000),
            "and heard the client's answers: " + signals());
        require(lobby.received(packet -> packet[0] == (byte) configStatus || packet[0] == (byte) playStatus).size() == 6,
            "the server heard exactly one declined answer per denied offer and the client's four");
      }
    }
  }

  // ---------------------------------------------------------------- 1.12.2

  private static void anOlderClientsServerPacks(Path plugins) throws Exception {
    VelocityCompatTests.installSignals();
    ProtocolDefinition p = ProtocolDefinition.forVersion(340);
    int send = p.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_RESOURCE_PACK_SEND);
    int status = p.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_RESOURCE_PACK_STATUS);
    int joinGame = p.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN);
    try (PackServer lobby = new PackServer(340, List.of())) {
      ConduitConfiguration configuration = VelocityCompatTests.configuration(List.of(lobby.server("lobby")));
      try (MinecraftProxy proxy = new MinecraftProxy(configuration, Authenticators.create(configuration.authentication()), RsaKeys.generate(), plugins)) {
        Thread.ofPlatform().daemon().start(() -> { try { proxy.serve(); } catch (Exception ignored) { } });
        try (Socket client = new Socket("127.0.0.1", proxy.port())) {
          client.setSoTimeout(15_000);
          InputStream in = client.getInputStream();
          OutputStream out = client.getOutputStream();
          MinecraftFrames.write(out, new Handshake(340, "localhost", 25565, 2).encode());
          MinecraftFrames.write(out, bytes(0x00, body -> MinecraftOutput.string(body, "Olde")));
          require(MinecraftFrames.read(in, 8192)[0] == 0x02, "Login Success");
          readUntil(in, packet -> packet[0] == (byte) joinGame);

          byte[] sendKeep = send(send, "https://server.invalid/keep.zip", "cc".repeat(20));
          lobby.send(send(send, "https://server.invalid/deny.zip", "aa".repeat(20)));
          lobby.send(send(send, "https://server.invalid/swap.zip", "bb".repeat(20)));
          lobby.send(sendKeep);
          List<byte[]> sent = readUntil(in, packet -> Arrays.equals(packet, sendKeep)).stream().filter(packet -> packet[0] == (byte) send).toList();
          require(sent.size() == 2 && Arrays.equals(sent.get(0), send(send, REPLACEMENT_URL, REPLACEMENT_HASH)),
              "the denied pack never arrives, the replacement does, then the kept pack as the server wrote it: " + describe(sent));
          require(lobby.await(new byte[] {(byte) status, 1}), "the server is told its denied pack was declined, by the result alone");
          // 1.12.2 answers name no pack: the answers go in order, the replacement's first.
          for (int result : new int[] {3, 0, 1}) MinecraftFrames.write(out, new byte[] {(byte) status, (byte) result});
          require(waitFor(() -> lobby.received(packet -> packet[0] == (byte) status).size() == 4, 5_000), "four answers reached the server");
          List<byte[]> answers = lobby.received(packet -> packet[0] == (byte) status);
          require(Arrays.equals(answers.get(1), new byte[] {(byte) status, 3}) && Arrays.equals(answers.get(2), new byte[] {(byte) status, 0})
              && Arrays.equals(answers.get(3), new byte[] {(byte) status, 1}), "each as the client wrote it: " + describe(answers));
        }
        require(waitFor(() -> signals().containsAll(List.of("status:" + REPLACEMENT + ":ACCEPTED:DOWNSTREAM_SERVER",
            "status:" + REPLACEMENT + ":SUCCESSFUL:DOWNSTREAM_SERVER")) && signals().stream().anyMatch(signal -> signal.endsWith(":DECLINED:DOWNSTREAM_SERVER")), 5_000),
            "the plugin heard the replacement loaded and the kept pack declined: " + signals());
        require(signals().stream().filter(signal -> signal.startsWith("send:")).count() == 3 && signals().stream().noneMatch(signal -> signal.startsWith("remove:")),
            "three offers put to the plugin, and no removal before 1.20.3: " + signals());
      }
    }
  }

  // ---------------------------------------------------------------- 1.8, on the bookkeeping

  /** 1.8's answers carry the pack's hash: the replacement's goes back to the server as its own pack's. */
  private static void legacyHashAnswersAreAboutTheServersPack() throws Exception {
    ProtocolDefinition v47 = ProtocolDefinition.forVersion(47);
    List<byte[]> written = new CopyOnWriteArrayList<>();
    List<byte[]> answered = new CopyOnWriteArrayList<>();
    ResourcePack replacement = new ResourcePack(REPLACEMENT, REPLACEMENT_URL, REPLACEMENT_HASH, true, Text.empty());
    ClientResourcePacks packs = new ClientResourcePacks(null, v47, written::add, event -> { }, new ClientResourcePacks.Server() {
      @Override public ResourcePack offered(ResourcePack pack) {
        return pack.url().contains("deny") ? null : pack.url().contains("swap") ? replacement : pack;
      }
      @Override public boolean removed(Optional<UUID> id) { return true; }
      @Override public void answer(byte[] answer) { answered.add(answer); }
    });
    int send = v47.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_RESOURCE_PACK_SEND);
    require(packs.beforeWrite(ConnectionState.PLAY, send(send, "https://server.invalid/deny.zip", "aa".repeat(20))) == null
        && answered.size() == 1 && Arrays.equals(answered.getFirst(), status18("aa".repeat(20), 1)), "denied: nothing to write, and the server hears it declined, by its hash");
    require(Arrays.equals(packs.beforeWrite(ConnectionState.PLAY, send(send, "https://server.invalid/swap.zip", "bb".repeat(20))),
        send(send, REPLACEMENT_URL, REPLACEMENT_HASH)), "replaced: the client is sent the replacement");
    require(packs.fromClient(ConnectionState.PLAY, status18(REPLACEMENT_HASH, 3)) && packs.fromClient(ConnectionState.PLAY, status18(REPLACEMENT_HASH, 0))
        && answered.size() == 3 && Arrays.equals(answered.get(1), status18("bb".repeat(20), 3)) && Arrays.equals(answered.get(2), status18("bb".repeat(20), 0)),
        "the client's answers go to the server with its own pack's hash");
    require(packs.offered().getFirst().fromServer() && packs.offered().getFirst().loaded() && packs.offered().getFirst().pack().equals(replacement),
        "and the client holds the replacement, loaded");
  }

  // ---------------------------------------------------------------- packets

  private static byte[] push(int id, UUID pack, String url, String hash) throws Exception { return push(id, pack, url, hash, false); }
  private static byte[] push(int id, UUID pack, String url, String hash, boolean required) throws Exception {
    return bytes(id, out -> {
      uuid(out, pack); MinecraftOutput.string(out, url); MinecraftOutput.string(out, hash); out.writeBoolean(required); out.writeBoolean(false);
    });
  }
  private static byte[] pop(int id, UUID pack) throws Exception {
    return bytes(id, out -> { out.writeBoolean(pack != null); if (pack != null) uuid(out, pack); });
  }
  private static byte[] send(int id, String url, String hash) throws Exception {
    return bytes(id, out -> { MinecraftOutput.string(out, url); MinecraftOutput.string(out, hash); });
  }
  private static byte[] status765(int id, UUID pack, int result) throws Exception {
    return bytes(id, out -> { uuid(out, pack); MinecraftOutput.varInt(out, result); });
  }
  private static void uuid(java.io.DataOutputStream out, UUID id) throws java.io.IOException {
    out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits());
  }
  private static String describe(List<byte[]> packets) {
    return packets.stream().map(packet -> java.util.HexFormat.of().formatHex(packet)).toList().toString();
  }

  @SuppressWarnings("unchecked")
  private static Queue<String> signals() { return (Queue<String>) System.getProperties().get(VelocityCompatTests.SIGNALS); }

  /**
   * A scripted server of one release: logs the player in, sends its Configuration packets and then
   * Finish Configuration (1.20.4), then Join Game, and keeps every packet it is sent.
   */
  static final class PackServer implements AutoCloseable {
    private final ServerSocket listener = new ServerSocket(0);
    private final List<byte[]> received = Collections.synchronizedList(new java.util.ArrayList<>());
    private volatile OutputStream out;
    PackServer(int protocol, List<byte[]> configuration) throws java.io.IOException {
      Thread.ofPlatform().daemon().start(() -> {
        try (Socket socket = listener.accept()) {
          InputStream in = socket.getInputStream();
          OutputStream output = socket.getOutputStream();
          MinecraftFrames.read(in, 8192);
          byte[] start = MinecraftFrames.read(in, 8192);
          String name = MinecraftInput.string(new DataInputStream(new ByteArrayInputStream(start, 1, start.length - 1)), 16);
          ProtocolDefinition p = ProtocolDefinition.forVersion(protocol);
          if (protocol == 765) {
            MinecraftFrames.write(output, bytes(0x02, body -> { uuid(body, new UUID(0, 1)); MinecraftOutput.string(body, name); MinecraftOutput.varInt(body, 0); }));
            MinecraftFrames.read(in, 8192);
            for (byte[] packet : configuration) MinecraftFrames.write(output, packet);
            byte finishOut = (byte) p.id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_FINISH);
            byte finishIn = (byte) p.id(ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_FINISH);
            MinecraftFrames.write(output, new byte[] {finishOut});
            byte[] packet;
            do {
              packet = MinecraftFrames.read(in, 1 << 16);
              received.add(packet);
            } while (!(packet.length == 1 && packet[0] == finishIn));
            MinecraftFrames.write(output, ModLoaderTests.joinGame765());
          } else {
            MinecraftFrames.write(output, bytes(0x02, body -> { MinecraftOutput.string(body, new UUID(0, 1).toString()); MinecraftOutput.string(body, name); }));
            MinecraftFrames.write(output, bytes(p.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN), body -> {
              body.writeInt(1); body.writeByte(0); body.writeInt(0); body.writeByte(1); body.writeByte(20);
              MinecraftOutput.string(body, "flat"); body.writeBoolean(false);
            }));
          }
          out = output;
          while (true) received.add(MinecraftFrames.read(in, 1 << 16));
        } catch (Exception closed) { /* the proxy went */ }
      });
    }
    BackendServer server(String name) { return new BackendServer(name, new InetSocketAddress("127.0.0.1", listener.getLocalPort())); }
    void send(byte[] packet) throws Exception {
      require(waitFor(() -> out != null, 10_000), "the server has a player in Play");
      synchronized (this) { MinecraftFrames.write(out, packet); }
    }
    List<byte[]> received(java.util.function.Predicate<byte[]> match) {
      synchronized (received) { return received.stream().filter(match).toList(); }
    }
    boolean await(byte[] packet) throws InterruptedException {
      return waitFor(() -> !received(candidate -> Arrays.equals(candidate, packet)).isEmpty(), 10_000);
    }
    @Override public void close() throws java.io.IOException { listener.close(); }
  }
}
