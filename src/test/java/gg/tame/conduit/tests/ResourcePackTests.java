// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.NativeApiTests.id;
import static gg.tame.conduit.tests.NativeApiTests.require;
import static gg.tame.conduit.tests.NativeApiTests.waitFor;

import gg.tame.conduit.api.event.player.PlayerResourcePackStatusEvent;
import gg.tame.conduit.api.event.player.PlayerServerConnectedEvent;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.api.player.ResourcePack;
import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.auth.Authenticators;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.crypto.RsaKeys;
import gg.tame.conduit.network.MinecraftProxy;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.ResourcePackPackets;
import gg.tame.conduit.session.ClientResourcePacks;
import gg.tame.conduit.tests.NativeApiTests.Backend;
import gg.tame.conduit.tests.NativeApiTests.Client;
import gg.tame.conduit.tests.NativeApiTests.Fixture;
import gg.tame.conduit.text.TextCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Resource packs: the bytes each era is sent and answers with, the proxy's gate and bookkeeping, a
 * scripted 1.8 client answering the proxy's packs and a server's across a switch, and a compiled
 * Velocity plugin offering packs through Velocity's API and Adventure's.
 */
public final class ResourcePackTests {
  public static void main(String[] arguments) throws Exception { run(); }

  private static final String URL = "https://example.invalid/pack.zip";
  private static final String HASH = "0123456789abcdef0123456789abcdef01234567";
  private static final ResourcePack PACK = new ResourcePack(new UUID(1, 2), URL, HASH, true, Text.of("Please"));

  public static void run() throws Exception {
    eachEraIsSentItsOwnPacket();
    eachEraIsReadBack();
    theGateHoldsOffersUntilTheClientHasAWorld();
    answersAreSortedByWhosePackItIs();
    aScriptedClientAnswersAcrossASwitch();
    aModernClientAnswersWhileReconfiguring();
    aVelocityPluginOffersPacksAndHearsTheAnswers();
    System.out.println("ResourcePackTests OK");
  }

  // ---------------------------------------------------------------- bytes

  private static void eachEraIsSentItsOwnPacket() throws Exception {
    // 1.8, 1.12.2, 1.16.5: url and hash only.
    for (int[] era : new int[][] {{47, 0x48}, {340, 0x34}, {754, 0x38}}) {
      require(Arrays.equals(offer(era[0]), bytes(era[1], out -> { MinecraftOutput.string(out, URL); MinecraftOutput.string(out, HASH); })),
          era[0] + ": Resource Pack Send with url and hash");
      require(ResourcePackPackets.remove(ProtocolDefinition.forVersion(era[0]), PACK.id()).isEmpty(), era[0] + " has no way to drop a pack");
    }
    // 1.20.1 (1.17-1.20.2): the required flag and a JSON prompt; an empty prompt is absent.
    require(Arrays.equals(offer(763), bytes(0x40, out -> {
      MinecraftOutput.string(out, URL); MinecraftOutput.string(out, HASH);
      out.writeBoolean(true); out.writeBoolean(true); MinecraftOutput.string(out, "{\"text\":\"Please\"}");
    })), "763: required flag and JSON prompt");
    ResourcePack quiet = new ResourcePack(PACK.id(), URL, "", false, Text.empty());
    require(Arrays.equals(ResourcePackPackets.offer(ProtocolDefinition.forVersion(763), quiet).orElseThrow(), bytes(0x40, out -> {
      MinecraftOutput.string(out, URL); MinecraftOutput.string(out, ""); out.writeBoolean(false); out.writeBoolean(false);
    })), "763: no hash, not required, no prompt");
    // 1.20.3+: Push with the UUID first and an NBT prompt; Pop by UUID or for every pack.
    for (int[] era : new int[][] {{765, 0x44, 0x43}, {767, 0x46, 0x45}, {776, 0x51, 0x50}}) {
      require(Arrays.equals(offer(era[0]), bytes(era[1], out -> {
        out.writeLong(1); out.writeLong(2); MinecraftOutput.string(out, URL); MinecraftOutput.string(out, HASH);
        out.writeBoolean(true); out.writeBoolean(true); TextCodec.write(out, Text.of("Please"), era[0]);
      })), era[0] + ": Push");
      byte[] prompt = Arrays.copyOfRange(offer(era[0]), offer(era[0]).length - nbtLength(), offer(era[0]).length);
      require(prompt[0] == 0x0A, era[0] + ": the prompt is a network NBT compound, not a JSON string");
      require(Arrays.equals(ResourcePackPackets.remove(ProtocolDefinition.forVersion(era[0]), PACK.id()).orElseThrow(),
          bytes(era[2], out -> { out.writeBoolean(true); out.writeLong(1); out.writeLong(2); })), era[0] + ": Pop one");
      require(Arrays.equals(ResourcePackPackets.remove(ProtocolDefinition.forVersion(era[0]), null).orElseThrow(),
          bytes(era[2], out -> out.writeBoolean(false))), era[0] + ": Pop every pack");
    }
    require(ResourcePackPackets.offer(ProtocolDefinition.forVersion(5), PACK).isEmpty(), "1.7 has no resource-pack packet");
    require(ResourcePackPackets.offer(ProtocolDefinition.forVersion(764), PACK).orElseThrow()[0] == 0x42, "1.20.2 still has Send");
  }

  private static void eachEraIsReadBack() throws Exception {
    ProtocolDefinition v47 = ProtocolDefinition.forVersion(47), v340 = ProtocolDefinition.forVersion(340),
        v764 = ProtocolDefinition.forVersion(764), v765 = ProtocolDefinition.forVersion(765), v767 = ProtocolDefinition.forVersion(767);
    // The client's answers.
    var answer = ResourcePackPackets.answer(v47, ConnectionState.PLAY, bytes(0x19, out -> { MinecraftOutput.string(out, HASH); MinecraftOutput.varInt(out, 0); })).orElseThrow();
    require(answer.hash().orElseThrow().equals(HASH) && answer.id().isEmpty() && answer.status().orElseThrow() == ResourcePack.Status.LOADED, "1.8: hash, then loaded");
    answer = ResourcePackPackets.answer(v340, ConnectionState.PLAY, bytes(0x18, out -> MinecraftOutput.varInt(out, 1))).orElseThrow();
    require(answer.hash().isEmpty() && answer.status().orElseThrow() == ResourcePack.Status.DECLINED, "1.12.2: the result alone");
    answer = ResourcePackPackets.answer(v765, ConnectionState.PLAY, bytes(0x28, out -> { out.writeLong(1); out.writeLong(2); MinecraftOutput.varInt(out, 3); })).orElseThrow();
    require(answer.id().orElseThrow().equals(PACK.id()) && answer.status().orElseThrow() == ResourcePack.Status.ACCEPTED, "1.20.4: the pack, then accepted");
    answer = ResourcePackPackets.answer(v765, ConnectionState.CONFIGURATION, bytes(0x05, out -> { out.writeLong(1); out.writeLong(2); MinecraftOutput.varInt(out, 4); })).orElseThrow();
    require(answer.status().orElseThrow() == ResourcePack.Status.DOWNLOADED, "1.20.4 answers in Configuration too");
    answer = ResourcePackPackets.answer(v767, ConnectionState.PLAY, bytes(0x2B, out -> { out.writeLong(1); out.writeLong(2); MinecraftOutput.varInt(out, 9); })).orElseThrow();
    require(answer.status().isEmpty(), "a result no release defines is no status");
    require(ResourcePackPackets.answer(v765, ConnectionState.PLAY, bytes(0x27, out -> MinecraftOutput.varInt(out, 0))).isEmpty(), "another packet is not an answer");

    // A server's offers and removals, in each family and state.
    var legacy = (ResourcePackPackets.Offer) ResourcePackPackets.read(v47, ConnectionState.PLAY, offer(47)).orElseThrow();
    require(!legacy.named() && legacy.pack().orElseThrow().url().equals(URL) && legacy.pack().orElseThrow().hash().equals(HASH), "1.8 Send read back");
    var pushed = (ResourcePackPackets.Offer) ResourcePackPackets.read(v765, ConnectionState.PLAY, offer(765)).orElseThrow();
    require(pushed.named() && pushed.pack().orElseThrow().equals(PACK), "1.20.4 Push read back whole, prompt included: " + pushed);
    byte[] configPush = offer(765);
    configPush[0] = 0x07;
    require(((ResourcePackPackets.Offer) ResourcePackPackets.read(v765, ConnectionState.CONFIGURATION, configPush).orElseThrow()).id().equals(PACK.id()),
        "1.20.4 Push in Configuration");
    byte[] configSend = ResourcePackPackets.offer(v764, PACK).orElseThrow();
    configSend[0] = 0x06;
    var configured = (ResourcePackPackets.Offer) ResourcePackPackets.read(v764, ConnectionState.CONFIGURATION, configSend).orElseThrow();
    require(!configured.named() && configured.pack().orElseThrow().required() && configured.pack().orElseThrow().prompt().plain().equals("Please"),
        "1.20.2 Send in Configuration, with its JSON prompt");
    var popped = (ResourcePackPackets.Removal) ResourcePackPackets.read(v765, ConnectionState.PLAY, ResourcePackPackets.remove(v765, null).orElseThrow()).orElseThrow();
    require(popped.id().isEmpty(), "Pop of every pack read back");
    byte[] oddHash = bytes(0x48, out -> { MinecraftOutput.string(out, URL); MinecraftOutput.string(out, "not-a-sha1"); });
    require(((ResourcePackPackets.Offer) ResourcePackPackets.read(v47, ConnectionState.PLAY, oddHash).orElseThrow()).pack().orElseThrow().hash().isEmpty(),
        "a server's hash the API cannot hold is dropped, and the pack still followed");
  }

  // ---------------------------------------------------------------- bookkeeping, no sockets

  private static void theGateHoldsOffersUntilTheClientHasAWorld() throws Exception {
    ProtocolDefinition v765 = ProtocolDefinition.forVersion(765);
    List<byte[]> written = new CopyOnWriteArrayList<>();
    ClientResourcePacks packs = new ClientResourcePacks(null, v765, written::add, event -> { });
    ResourcePack second = new ResourcePack(new UUID(3, 4), URL + "?2", "", false, Text.empty());
    require(packs.offer(PACK) && written.isEmpty(), "before the first Join Game an offer waits");
    byte[] joinGame = bytes(v765.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN), out -> out.writeInt(1));
    packs.beforeWrite(ConnectionState.PLAY, joinGame);
    packs.afterWrite(ConnectionState.PLAY, joinGame);
    require(written.size() == 1 && Arrays.equals(written.getFirst(), offer(765)), "and goes with it");
    // A switch: Start Configuration takes the world, and nothing may be written until the next Join Game.
    byte[] start = bytes(v765.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_START_CONFIGURATION), out -> { });
    packs.beforeWrite(ConnectionState.PLAY, start);
    require(packs.remove(PACK.id()) && packs.offer(second) && written.size() == 1, "while switching, removals and offers wait");
    packs.beforeWrite(ConnectionState.PLAY, joinGame);
    packs.afterWrite(ConnectionState.PLAY, joinGame);
    require(written.size() == 3 && Arrays.equals(written.get(1), ResourcePackPackets.remove(v765, PACK.id()).orElseThrow())
        && Arrays.equals(written.get(2), ResourcePackPackets.offer(v765, second).orElseThrow()), "then go, the removal first");
    // A pack that never reached the client is taken back without a word to it.
    packs.beforeWrite(ConnectionState.PLAY, start);
    ResourcePack third = new ResourcePack(new UUID(5, 6), URL + "?3", "", false, Text.empty());
    require(packs.offer(third) && packs.remove(third.id()), "offered and taken back while away");
    packs.beforeWrite(ConnectionState.PLAY, joinGame);
    packs.afterWrite(ConnectionState.PLAY, joinGame);
    require(written.size() == 3, "nothing to say about it");
    require(packs.clear() && written.size() == 4 && Arrays.equals(written.get(3), ResourcePackPackets.remove(v765, null).orElseThrow())
        && packs.offered().isEmpty(), "clear drops every pack at once");
    packs.close();
    require(!packs.offer(PACK), "a closed session takes no offers");

    ClientResourcePacks old = new ClientResourcePacks(null, ProtocolDefinition.forVersion(340), written::add, event -> { });
    require(!old.remove(PACK.id()) && !old.clear(), "before 1.20.3 a client cannot be told to drop a pack");
    require(!new ClientResourcePacks(null, ProtocolDefinition.forVersion(5), written::add, event -> { }).offer(PACK), "1.7 cannot be offered one");
  }

  private static void answersAreSortedByWhosePackItIs() throws Exception {
    // 1.20.4: every answer names its pack.
    ProtocolDefinition v765 = ProtocolDefinition.forVersion(765);
    List<PlayerResourcePackStatusEvent> heard = new CopyOnWriteArrayList<>();
    ClientResourcePacks packs = new ClientResourcePacks(null, v765, packet -> { }, heard::add);
    byte[] joinGame = bytes(v765.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN), out -> out.writeInt(1));
    packs.afterWrite(ConnectionState.PLAY, joinGame);
    packs.offer(PACK);
    UUID serverPack = new UUID(9, 9);
    packs.beforeWrite(ConnectionState.PLAY, bytes(0x44, out -> {
      out.writeLong(9); out.writeLong(9); MinecraftOutput.string(out, "https://server.invalid/p.zip"); MinecraftOutput.string(out, "");
      out.writeBoolean(false); out.writeBoolean(false);
    }));
    require(!packs.fromClient(ConnectionState.PLAY, status765(serverPack, 3)), "the server's pack: its answer goes on to the server");
    require(packs.fromClient(ConnectionState.PLAY, status765(PACK.id(), 3)), "the proxy's: its answer stops here");
    require(packs.fromClient(ConnectionState.CONFIGURATION, bytes(0x05, out -> { out.writeLong(1); out.writeLong(2); MinecraftOutput.varInt(out, 0); })),
        "even when it comes during a switch, in Configuration");
    require(!packs.fromClient(ConnectionState.PLAY, status765(new UUID(7, 7), 0)), "a pack nobody offered through the proxy is the server's business");
    require(heard.size() == 3 && heard.get(0).fromServer() && !heard.get(1).fromServer() && heard.get(2).status() == ResourcePack.Status.LOADED,
        "each answer about a known pack is heard, marked with whose it is: " + heard);
    List<ResourcePack.Offered> offered = packs.offered();
    require(offered.size() == 2 && offered.get(0).loaded() && !offered.get(0).fromServer() && !offered.get(1).loaded() && offered.get(1).fromServer(),
        "the proxy's loaded, the server's still pending: " + offered);
    require(!packs.fromClient(ConnectionState.PLAY, status765(serverPack, 1)) && packs.offered().size() == 1, "a declined pack is gone");
    packs.beforeWrite(ConnectionState.PLAY, bytes(0x43, out -> out.writeBoolean(false)));
    require(packs.offered().isEmpty(), "a server's Pop of every pack takes the proxy's with it, as the client does");

    // 1.12.2: nothing is named, so answers are matched to offers in the order the client got them.
    ProtocolDefinition v340 = ProtocolDefinition.forVersion(340);
    heard.clear();
    ClientResourcePacks old = new ClientResourcePacks(null, v340, packet -> { }, heard::add);
    byte[] joinGame340 = bytes(v340.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN), out -> out.writeInt(1));
    old.afterWrite(ConnectionState.PLAY, joinGame340);
    old.beforeWrite(ConnectionState.PLAY, bytes(0x34, out -> { MinecraftOutput.string(out, "https://server.invalid/p.zip"); MinecraftOutput.string(out, ""); }));
    old.offer(PACK);
    byte[] accepted = bytes(0x18, out -> MinecraftOutput.varInt(out, 3));
    byte[] loaded = bytes(0x18, out -> MinecraftOutput.varInt(out, 0));
    require(!old.fromClient(ConnectionState.PLAY, accepted) && !old.fromClient(ConnectionState.PLAY, loaded), "the server's offer came first: both its answers go on");
    require(old.fromClient(ConnectionState.PLAY, accepted) && old.fromClient(ConnectionState.PLAY, loaded), "then the proxy's: both stop here");
    require(heard.size() == 4 && heard.get(0).fromServer() && heard.get(1).fromServer() && !heard.get(2).fromServer() && !heard.get(3).fromServer(), "in that order: " + heard);
    require(old.offered().size() == 1 && !old.offered().getFirst().fromServer() && old.offered().getFirst().loaded(),
        "a client before 1.20.3 holds one pack: the last one it loaded");
    require(!old.fromClient(ConnectionState.PLAY, loaded), "an answer with nothing left to answer goes on to the server");
  }

  private static byte[] status765(UUID id, int result) throws IOException {
    return bytes(0x28, out -> { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); MinecraftOutput.varInt(out, result); });
  }

  // ---------------------------------------------------------------- a real proxy

  /**
   * A 1.8 player is offered the proxy's pack and its server's, answers both, and moves to another
   * server, which offers one of its own. Each server hears only the answers about its own packs.
   */
  private static void aScriptedClientAnswersAcrossASwitch() throws Exception {
    try (Backend lobby = new Backend("lobby"); Backend survival = new Backend("survival");
         Fixture proxy = new Fixture(List.of(lobby, survival), List.of("lobby"), List.of("lobby"))) {
      try (Client client = Client.join(proxy.port(), "Packer")) {
        require(proxy.recorder.await(PlayerServerConnectedEvent.class, 1), "joined");
        Player player = proxy.runtime.player("Packer").orElseThrow();
        require(player.sendResourcePack(PACK), "offered");
        require(client.await(packet -> id(packet) == 0x48 && sentUrl(packet).equals(URL)), "the client gets the proxy's pack");
        String serverHash = "ffffffffffffffffffffffffffffffffffffffff";
        lobby.send(bytes(0x48, out -> { MinecraftOutput.string(out, "https://lobby.invalid/p.zip"); MinecraftOutput.string(out, serverHash); }));
        require(client.await(packet -> id(packet) == 0x48 && sentUrl(packet).startsWith("https://lobby")), "and the lobby's");
        // 1.8 names the hash, so the answers need not come in the order of the offers.
        client.send(status18(serverHash, 0));
        client.send(status18(HASH, 3));
        client.send(status18(HASH, 0));
        require(lobby.await(packet -> id(packet) == 0x19), "the lobby hears about its pack");
        require(waitFor(() -> proxy.recorder.of(PlayerResourcePackStatusEvent.class).size() == 3, 5_000), "three answers heard");
        Thread.sleep(200);
        List<byte[]> lobbyHeard = lobby.received(packet -> id(packet) == 0x19);
        require(lobbyHeard.size() == 1 && statusHash(lobbyHeard.getFirst()).equals(serverHash), "and only about its own pack");
        List<PlayerResourcePackStatusEvent> events = proxy.recorder.of(PlayerResourcePackStatusEvent.class);
        require(events.get(0).fromServer() && events.get(0).status() == ResourcePack.Status.LOADED
            && !events.get(1).fromServer() && events.get(1).status() == ResourcePack.Status.ACCEPTED
            && events.get(2).pack().equals(PACK) && events.get(2).status() == ResourcePack.Status.LOADED, "each marked with whose pack it is: " + events);

        // Moving on. The proxy's pack is the proxy's: it stays, and is not offered again.
        require(player.connectWithResult(proxy.runtime.servers().getServer("survival").orElseThrow()).get(15, TimeUnit.SECONDS).successful(), "on survival");
        Thread.sleep(300);
        require(client.received(packet -> id(packet) == 0x48 && sentUrl(packet).equals(URL)).size() == 1, "the proxy's pack is not offered again");
        require(player.resourcePacks().stream().anyMatch(offered -> offered.pack().equals(PACK) && offered.loaded() && !offered.fromServer()),
            "and is still counted as loaded: " + player.resourcePacks());
        String survivalHash = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
        survival.send(bytes(0x48, out -> { MinecraftOutput.string(out, "https://survival.invalid/p.zip"); MinecraftOutput.string(out, survivalHash); }));
        require(client.await(packet -> id(packet) == 0x48 && sentUrl(packet).startsWith("https://survival")), "survival's pack reaches the client");
        ResourcePack another = ResourcePack.of("https://example.invalid/second.zip", "dddddddddddddddddddddddddddddddddddddddd");
        require(player.sendResourcePack(another), "and so does another of the proxy's");
        require(client.await(packet -> id(packet) == 0x48 && sentUrl(packet).endsWith("second.zip")), "offered on survival");
        client.send(status18(another.hash(), 1));
        client.send(status18(survivalHash, 0));
        require(survival.await(packet -> id(packet) == 0x19), "survival hears about its pack");
        Thread.sleep(200);
        require(survival.received(packet -> id(packet) == 0x19).size() == 1 && statusHash(survival.received(packet -> id(packet) == 0x19).getFirst()).equals(survivalHash),
            "and only about its own");
        require(lobby.received(packet -> id(packet) == 0x19).size() == 1, "the lobby heard nothing more");
        require(player.resourcePacks().stream().noneMatch(offered -> offered.pack().equals(another)), "the declined pack is gone");
      }
    }
  }

  /**
   * A 1.20.4 client, DIRECT, switched: its answers name the pack, one of them arrives while it is
   * configuring for the new server, and an offer made after Start Configuration waits for the new world.
   */
  private static void aModernClientAnswersWhileReconfiguring() throws Exception {
    ProtocolDefinition p = ProtocolDefinition.forVersion(765);
    int configOut = p.id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_PLUGIN_MESSAGE);
    byte finishOut = (byte) p.id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_FINISH);
    byte finishIn = (byte) p.id(ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_FINISH);
    int joinGame = p.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN);
    int start = p.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_START_CONFIGURATION);
    byte configurationAck = (byte) p.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CONFIGURATION_ACKNOWLEDGED);
    ResourcePack second = new ResourcePack(new UUID(3, 4), URL + "?2", "", false, Text.empty());
    try (var lobbyListener = new java.net.ServerSocket(0); var otherListener = new java.net.ServerSocket(0)) {
      ModLoaderTests.Mock lobby = new ModLoaderTests.Mock(lobbyListener, "lobby", new byte[0], configOut, finishOut, finishIn);
      ModLoaderTests.Mock other = new ModLoaderTests.Mock(otherListener, "other", new byte[0], configOut, finishOut, finishIn);
      Thread.ofPlatform().daemon().start(lobby);
      Thread.ofPlatform().daemon().start(other);
      ConduitConfiguration configuration = new ConduitConfiguration(new java.net.InetSocketAddress("127.0.0.1", ModLoaderTests.reservePort()), 8192,
          gg.tame.conduit.config.ForwardingMode.NONE, java.util.Optional.empty(),
          List.of(new gg.tame.conduit.config.BackendServer("lobby", new java.net.InetSocketAddress("127.0.0.1", lobbyListener.getLocalPort())),
              new gg.tame.conduit.config.BackendServer("other", new java.net.InetSocketAddress("127.0.0.1", otherListener.getLocalPort()))),
          List.of("lobby"), List.of());
      try (MinecraftProxy proxy = new MinecraftProxy(configuration)) {
        List<PlayerResourcePackStatusEvent> heard = new CopyOnWriteArrayList<>();
        gg.tame.conduit.api.plugin.ConduitPlugin listener = new gg.tame.conduit.api.plugin.ConduitPlugin() { };
        listener.attach(new gg.tame.conduit.api.plugin.PluginDescription("packs", "packs", "1", "Main", 1, List.of()), proxy.runtime(),
            java.util.logging.Logger.getLogger("packs"), null, proxy.runtime().scheduler());
        proxy.runtime().events().register(listener, new Object() {
          @gg.tame.conduit.api.event.Subscribe public void heard(PlayerResourcePackStatusEvent event) { heard.add(event); }
        });
        Thread.ofPlatform().daemon().start(() -> { try { proxy.serve(); } catch (Exception ignored) { } });
        try (var client = new java.net.Socket("127.0.0.1", proxy.port())) {
          client.setSoTimeout(15_000);
          var in = client.getInputStream();
          var out = client.getOutputStream();
          gg.tame.conduit.protocol.MinecraftFrames.write(out, new gg.tame.conduit.protocol.Handshake(765, "localhost", 25565, 2).encode());
          gg.tame.conduit.protocol.MinecraftFrames.write(out, ModLoaderTests.loginStart());
          require(gg.tame.conduit.protocol.MinecraftFrames.read(in, 8192)[0] == 0x02, "Login Success");
          gg.tame.conduit.protocol.MinecraftFrames.write(out, new byte[] {0x03});
          ModLoaderTests.readConfiguration(in, finishOut);
          gg.tame.conduit.protocol.MinecraftFrames.write(out, new byte[] {finishIn});
          readUntil(in, packet -> packet[0] == (byte) joinGame);

          Player player = proxy.runtime().player("playr").orElseThrow();
          require(player.sendResourcePack(PACK), "offered");
          byte[] pushed = readUntil(in, packet -> packet[0] == 0x44).getLast();
          require(Arrays.equals(pushed, offer(765)), "the client is pushed the pack, named by its UUID");
          gg.tame.conduit.protocol.MinecraftFrames.write(out, status765(PACK.id(), 3));

          gg.tame.conduit.protocol.MinecraftFrames.write(out, ModLoaderTests.command(
              p.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT_COMMAND), "server other"));
          readUntil(in, packet -> packet[0] == (byte) start);
          require(player.sendResourcePack(second), "offered while the client reconfigures");
          gg.tame.conduit.protocol.MinecraftFrames.write(out, new byte[] {configurationAck});
          // The download finishes while the client is configuring for the other server.
          gg.tame.conduit.protocol.MinecraftFrames.write(out, bytes(0x05, body -> { body.writeLong(1); body.writeLong(2); MinecraftOutput.varInt(body, 0); }));
          List<byte[]> configuring = ModLoaderTests.readConfiguration(in, finishOut);
          require(configuring.stream().noneMatch(packet -> packet[0] == 0x44 || packet[0] == 0x07), "nothing of the proxy's reaches a client in Configuration");
          gg.tame.conduit.protocol.MinecraftFrames.write(out, new byte[] {finishIn});
          List<byte[]> after = readUntil(in, packet -> packet[0] == 0x44);
          require(after.stream().anyMatch(packet -> packet[0] == (byte) joinGame)
              && Arrays.equals(after.getLast(), ResourcePackPackets.offer(p, second).orElseThrow()), "the held pack goes after the new Join Game");
          require(waitFor(() -> heard.size() == 2, 5_000) && heard.get(0).status() == ResourcePack.Status.ACCEPTED
              && heard.get(1).status() == ResourcePack.Status.LOADED && heard.stream().noneMatch(PlayerResourcePackStatusEvent::fromServer),
              "both answers heard, the one sent while configuring included: " + heard);
          Thread.sleep(200);
          require(lobby.received.stream().noneMatch(packet -> packet[0] == 0x28), "the lobby never hears the proxy's pack answered");
          require(other.received.stream().noneMatch(packet -> packet[0] == 0x05 || packet[0] == 0x28), "nor does the server switched to");
          require(player.resourcePacks().equals(List.of(new ResourcePack.Offered(PACK, false, true), new ResourcePack.Offered(second, false, false))),
              "one loaded, one pending: " + player.resourcePacks());
        }
      }
    }
  }

  private static List<byte[]> readUntil(java.io.InputStream in, java.util.function.Predicate<byte[]> last) throws IOException {
    List<byte[]> read = new java.util.ArrayList<>();
    while (true) {
      byte[] packet = gg.tame.conduit.protocol.MinecraftFrames.read(in, 1 << 20);
      read.add(packet);
      if (last.test(packet)) return read;
    }
  }

  private static byte[] status18(String hash, int result) throws IOException {
    return bytes(0x19, out -> { MinecraftOutput.string(out, hash); MinecraftOutput.varInt(out, result); });
  }

  private static String sentUrl(byte[] packet) {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet, 1, packet.length - 1))) {
      return MinecraftInput.string(input, 1 << 17);
    } catch (IOException unreadable) { return ""; }
  }

  private static String statusHash(byte[] packet) { return sentUrl(packet); }

  // ---------------------------------------------------------------- Velocity

  private static final String VPACK = """
      package vpack;
      import com.velocitypowered.api.event.Subscribe;
      import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent;
      import com.velocitypowered.api.event.player.ServerConnectedEvent;
      import com.velocitypowered.api.plugin.Plugin;
      import com.velocitypowered.api.proxy.ProxyServer;
      import com.velocitypowered.api.proxy.player.ResourcePackInfo;
      import java.net.URI;
      import java.util.UUID;
      import javax.inject.Inject;
      import net.kyori.adventure.resource.ResourcePackCallback;
      import net.kyori.adventure.resource.ResourcePackRequest;
      import net.kyori.adventure.text.Component;

      @Plugin(id = "vpack", name = "VPack", version = "1.0")
      public final class Main {
        @SuppressWarnings("unchecked")
        static void signal(String value) { ((java.util.Queue<String>) System.getProperties().get("velocity.test.signals")).add(value); }
        private final ProxyServer proxy;
        @Inject public Main(ProxyServer proxy) { this.proxy = proxy; }

        @Subscribe public void connected(ServerConnectedEvent event) {
          byte[] hash = new byte[20];
          java.util.Arrays.fill(hash, (byte) 0xAB);
          ResourcePackInfo info = proxy.createResourcePackBuilder("https://example.invalid/velocity.zip")
              .setHash(hash).setShouldForce(true).setPrompt(Component.text("Take it")).build();
          signal("built:" + info.getId() + ":" + info.getOrigin() + ":" + info.getShouldForce());
          event.getPlayer().sendResourcePackOffer(info);
          event.getPlayer().sendResourcePacks(ResourcePackRequest.resourcePackRequest()
              .packs(net.kyori.adventure.resource.ResourcePackInfo.resourcePackInfo(new UUID(4, 2), URI.create("https://example.invalid/adventure.zip"),
                  "cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd"))
              .callback((id, status, audience) -> signal("callback:" + id + ":" + status + ":" + (audience == event.getPlayer())))
              .build());
          signal("pending:" + event.getPlayer().getPendingResourcePacks().size());
          event.getPlayer().clearResourcePacks();
          signal("cleared-quietly");
        }
        @Subscribe public void status(PlayerResourcePackStatusEvent event) {
          signal("status:" + event.getPackId() + ":" + event.getStatus() + ":" + event.getPackInfo().getOrigin() + ":" + event.getPackInfo().getUrl()
              + ":applied=" + event.getPlayer().getAppliedResourcePacks().size());
        }
      }
      """;

  private static void aVelocityPluginOffersPacksAndHearsTheAnswers() throws Exception {
    VelocityCompatTests.installSignals();
    Path root = TempFiles.dir("velocity-packs");
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    VelocityCompatTests.jar(VelocityCompatTests.compile(root, "vpack.Main", VPACK, List.of(), true), plugins.resolve("vpack.jar"), null);
    try (Backend lobby = new Backend("lobby")) {
      ConduitConfiguration configuration = VelocityCompatTests.configuration(List.of(lobby.server()));
      MinecraftProxy proxy = new MinecraftProxy(configuration, Authenticators.create(configuration.authentication()), RsaKeys.generate(), plugins);
      Thread serving = Thread.ofPlatform().daemon().name("velocity-packs-serve").start(() -> {
        try { proxy.serve(); } catch (IOException ignored) { }
      });
      try (Client client = Client.join(proxy.port(), "Velo")) {
        UUID built = UUID.nameUUIDFromBytes("https://example.invalid/velocity.zip".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        VelocityCompatTests.awaitSignal("built:" + built + ":PLUGIN_ON_PROXY:true");
        VelocityCompatTests.awaitSignal("pending:2");
        VelocityCompatTests.awaitSignal("cleared-quietly");
        require(client.await(packet -> id(packet) == 0x48 && sentUrl(packet).endsWith("velocity.zip"))
            && client.await(packet -> id(packet) == 0x48 && sentUrl(packet).endsWith("adventure.zip")), "both packs reach the 1.8 client");
        client.send(status18("abababababababababababababababababababab", 0));
        VelocityCompatTests.awaitSignal("status:" + built + ":SUCCESSFUL:PLUGIN_ON_PROXY:https://example.invalid/velocity.zip:applied=1");
        client.send(status18("cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd", 1));
        VelocityCompatTests.awaitSignal("callback:" + new UUID(4, 2) + ":DECLINED:true");
        VelocityCompatTests.awaitSignal("status:" + new UUID(4, 2) + ":DECLINED:PLUGIN_ON_PROXY:https://example.invalid/adventure.zip:applied=1");
        Thread.sleep(200);
        require(lobby.received(packet -> id(packet) == 0x19).isEmpty(), "the server never hears about the plugin's packs");
      } finally {
        proxy.close();
        serving.join(10_000);
      }
    }
  }

  // ---------------------------------------------------------------- helpers

  private static byte[] offer(int protocol) throws IOException {
    return ResourcePackPackets.offer(ProtocolDefinition.forVersion(protocol), PACK).orElseThrow();
  }

  private static int nbtLength() throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) { TextCodec.write(out, Text.of("Please"), 765); }
    return bytes.size();
  }

  private interface Body { void write(DataOutputStream output) throws IOException; }

  private static byte[] bytes(int id, Body body) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(out, id);
      body.write(out);
    }
    return bytes.toByteArray();
  }
}
