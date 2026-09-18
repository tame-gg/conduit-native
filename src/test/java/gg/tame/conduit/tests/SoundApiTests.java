// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.api.event.player.PlayerServerConnectedEvent;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.api.player.Sound;
import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.config.ForwardingMode;
import gg.tame.conduit.network.MinecraftProxy;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.DisplayPackets;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.PluginMessage;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.session.ClientDisplay;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Sounds the proxy plays by name: each packet byte for byte for every era of client (1.8's byte pitch
 * and no category, 1.9-1.19.2's Named Sound Effect with the seed from 1.19, 1.19.3's inline sound
 * events, 26.1's ui category, and the 1.12.2 stop channel), what a client that cannot take one is
 * sent (nothing), the gate, and scripted 1.8 and 1.20.4 sessions receiving them.
 */
public final class SoundApiTests {
  public static void main(String[] a) throws Exception { run(); }

  public static void run() throws Exception {
    soundsAreValidated();
    positionedSoundsPerEra();
    followingSoundsPerEra();
    stoppingSoundsPerEra();
    theGateDropsSoundsWithoutAWorld();
    aLegacyClientHearsWhatItCan();
    aModernClientHearsEverything();
    System.out.println("SoundApiTests OK");
  }

  static final String NAME = "minecraft:entity.experience_orb.pickup";
  /** Player category, half volume, pitch 1.5, seed 42. */
  static final Sound SOUND = new Sound(NAME, Sound.Source.PLAYER, 0.5f, 1.5f, OptionalLong.of(42));
  static final double X = 1.5, Y = 64, Z = -2.25;

  /** What {@code protocol} is sent for {@link #SOUND} at (X, Y, Z), with {@code id} its sound packet. */
  static byte[] at(int protocol, int id, int source) throws IOException {
    return DisplayApiTests.packet(id, out -> {
      if (protocol >= 761) { MinecraftOutput.varInt(out, 0); MinecraftOutput.string(out, NAME); out.writeBoolean(false); }
      else MinecraftOutput.string(out, NAME);
      if (protocol >= 107) MinecraftOutput.varInt(out, source);
      out.writeInt(12); out.writeInt(512); out.writeInt(-18);
      out.writeFloat(0.5f);
      if (protocol >= 201) out.writeFloat(1.5f); else out.writeByte(94);
      if (protocol >= 759) out.writeLong(42);
    });
  }

  /** What a 1.19.3+ {@code protocol} is sent for {@link #SOUND} following entity {@code entity}. */
  static byte[] following(int id, int source, int entity) throws IOException {
    return DisplayApiTests.packet(id, out -> {
      MinecraftOutput.varInt(out, 0); MinecraftOutput.string(out, NAME); out.writeBoolean(false);
      MinecraftOutput.varInt(out, source);
      MinecraftOutput.varInt(out, entity);
      out.writeFloat(0.5f); out.writeFloat(1.5f); out.writeLong(42);
    });
  }

  /** Stop Sound from 1.13: flags, then the source and name each flag says is there. */
  static byte[] stop(int id, Integer source, String name) throws IOException {
    return DisplayApiTests.packet(id, out -> {
      out.writeByte((source != null ? 1 : 0) | (name != null ? 2 : 0));
      if (source != null) MinecraftOutput.varInt(out, source);
      if (name != null) MinecraftOutput.string(out, name);
    });
  }

  /** 1.9.3-1.12.2's MC|StopSound: the category name, then the sound name, empty meaning any. */
  static byte[] stopChannel(int pluginMessageId, String name, String category) throws IOException {
    ByteArrayOutputStream payload = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(payload)) {
      MinecraftOutput.string(out, category);
      MinecraftOutput.string(out, name);
    }
    return new PluginMessage("MC|StopSound", payload.toByteArray()).encode(pluginMessageId);
  }

  private static void soundsAreValidated() {
    for (String bad : new String[] {"Minecraft:x", "a b", "minecraft:", ":x", "x:y:z", "entity.orb!"}) {
      try { Sound.of(bad, Sound.Source.MASTER, 1, 1); throw new AssertionError("accepted " + bad); }
      catch (IllegalArgumentException expected) { }
    }
    require(Sound.of("random.orb", Sound.Source.MASTER, 1, 1).name().equals("random.orb"), "a 1.8-style name without a namespace");
    for (float volume : new float[] {-1f, Float.NaN, Float.POSITIVE_INFINITY}) {
      try { Sound.of(NAME, Sound.Source.MASTER, volume, 1); throw new AssertionError("volume " + volume); }
      catch (IllegalArgumentException expected) { }
    }
    try { Sound.of(NAME, Sound.Source.MASTER, 1, Float.NaN); throw new AssertionError("NaN pitch"); }
    catch (IllegalArgumentException expected) { }
    require(Sound.of(NAME, Sound.Source.MASTER, 1, 1).seed().isEmpty() && SOUND.withSeed(7).seed().getAsLong() == 7, "seeds");
  }

  private static void positionedSoundsPerEra() throws Exception {
    record Era(int protocol, int id) {}
    for (Era era : List.of(new Era(5, 0x29), new Era(47, 0x29), new Era(340, 0x19), new Era(393, 0x1A), new Era(477, 0x19),
        new Era(573, 0x1A), new Era(754, 0x18), new Era(755, 0x19), new Era(759, 0x16), new Era(760, 0x17),
        new Era(761, 0x5E), new Era(762, 0x62), new Era(763, 0x62), new Era(764, 0x64), new Era(765, 0x66), new Era(766, 0x68),
        new Era(767, 0x68), new Era(768, 0x6F), new Era(770, 0x6E), new Era(773, 0x73), new Era(775, 0x75), new Era(776, 0x75))) {
      ProtocolDefinition p = ProtocolDefinition.forVersion(era.protocol());
      expect(DisplayPackets.soundAt(p, SOUND, X, Y, Z, 42), at(era.protocol(), era.id(), 7), "protocol " + era.protocol() + " positioned sound");
    }
    Sound ui = new Sound(NAME, Sound.Source.UI, 0.5f, 1.5f, OptionalLong.of(42));
    expect(DisplayPackets.soundAt(ProtocolDefinition.forVersion(776), ui, X, Y, Z, 42), at(776, 0x75, 10), "26.2 has the ui category");
    expect(DisplayPackets.soundAt(ProtocolDefinition.forVersion(774), ui, X, Y, Z, 42), at(774, 0x73, 0), "1.21.11 plays ui as master");
    expect(DisplayPackets.soundAt(ProtocolDefinition.forVersion(340), ui, X, Y, Z, 42), at(340, 0x19, 0), "and so does 1.12.2");
    byte[] high = DisplayPackets.soundAt(ProtocolDefinition.forVersion(47), Sound.of(NAME, Sound.Source.MASTER, 1, 9f), 0, 0, 0, 0).orElseThrow();
    require((high[high.length - 1] & 0xFF) == 255, "1.8's byte pitch is capped at 255");
  }

  private static void followingSoundsPerEra() throws Exception {
    record Era(int protocol, int id) {}
    for (Era era : List.of(new Era(761, 0x5D), new Era(762, 0x61), new Era(764, 0x63), new Era(765, 0x65), new Era(766, 0x67),
        new Era(767, 0x67), new Era(768, 0x6E), new Era(770, 0x6D), new Era(773, 0x72), new Era(776, 0x74))) {
      expect(DisplayPackets.soundFollowing(ProtocolDefinition.forVersion(era.protocol()), SOUND, 1234, 42),
          following(era.id(), 7, 1234), "protocol " + era.protocol() + " entity sound");
    }
    for (int protocol : new int[] {5, 47, 340, 393, 754, 759, 760}) {
      require(DisplayPackets.soundFollowing(ProtocolDefinition.forVersion(protocol), SOUND, 1234, 42).isEmpty(),
          "protocol " + protocol + " names an entity's sound only by registry id, so it is sent nothing");
    }
  }

  private static void stoppingSoundsPerEra() throws Exception {
    ProtocolDefinition p393 = ProtocolDefinition.forVersion(393);
    expect(DisplayPackets.stopSound(p393, null, null), stop(0x4C, null, null), "1.13 stop everything");
    expect(DisplayPackets.stopSound(p393, null, Sound.Source.MUSIC), stop(0x4C, 1, null), "1.13 stop a source");
    expect(DisplayPackets.stopSound(p393, NAME, null), stop(0x4C, null, NAME), "1.13 stop a sound");
    expect(DisplayPackets.stopSound(p393, NAME, Sound.Source.MUSIC), stop(0x4C, 1, NAME), "1.13 stop a sound in a source");
    record Era(int protocol, int id) {}
    for (Era era : List.of(new Era(477, 0x52), new Era(573, 0x53), new Era(755, 0x5D), new Era(760, 0x61), new Era(761, 0x5F),
        new Era(763, 0x63), new Era(765, 0x68), new Era(767, 0x6A), new Era(770, 0x70), new Era(776, 0x77))) {
      expect(DisplayPackets.stopSound(ProtocolDefinition.forVersion(era.protocol()), NAME, Sound.Source.WEATHER),
          stop(era.id(), 3, NAME), "protocol " + era.protocol() + " stop");
    }
    expect(DisplayPackets.stopSound(ProtocolDefinition.forVersion(776), null, Sound.Source.UI), stop(0x77, 10, null), "26.2 stops ui");
    expect(DisplayPackets.stopSound(ProtocolDefinition.forVersion(765), null, Sound.Source.UI), stop(0x68, 0, null), "1.20.4 has no ui");
    ProtocolDefinition p340 = ProtocolDefinition.forVersion(340);
    expect(DisplayPackets.stopSound(p340, null, null), stopChannel(0x18, "", ""), "1.12.2 stops everything through MC|StopSound");
    expect(DisplayPackets.stopSound(p340, NAME, Sound.Source.HOSTILE), stopChannel(0x18, NAME, "hostile"), "and by name and category");
    expect(DisplayPackets.stopSound(p340, null, Sound.Source.HOSTILE), stopChannel(0x18, "", "hostile"), "and a whole category");
    for (int protocol : new int[] {5, 47}) {
      require(DisplayPackets.stopSound(ProtocolDefinition.forVersion(protocol), NAME, null).isEmpty(), protocol + " cannot stop a sound");
    }
  }

  /** Nothing before Join Game or while reconfiguring, and the entity id is the one the client was last given. */
  private static void theGateDropsSoundsWithoutAWorld() throws Exception {
    ProtocolDefinition p = ProtocolDefinition.forVersion(765);
    List<byte[]> wire = new ArrayList<>();
    ClientDisplay display = new ClientDisplay(DisplayApiTests.dummyPlayer(), p, wire::add);
    display.playSound(SOUND);
    display.playSound(SOUND, X, Y, Z);
    display.stopSound(null, null);
    require(wire.isEmpty(), "no sound before Join Game");
    enter(display, DisplayApiTests.packet(0x29, out -> out.writeInt(1234)));
    display.playSound(SOUND);
    require(wire.size() == 1 && Arrays.equals(wire.get(0), following(0x65, 7, 1234)), "the player's own entity id, from Join Game");
    display.beforeWrite(ConnectionState.PLAY, PlayPackets.startConfiguration(p));
    display.playSound(SOUND);
    display.playSound(SOUND, X, Y, Z);
    require(wire.size() == 1, "nothing while the client is reconfigured, and nothing held for later");
    enter(display, DisplayApiTests.packet(0x29, out -> out.writeInt(99)));
    require(wire.size() == 1, "a dropped sound is not played on return");
    display.playSound(SOUND);
    require(Arrays.equals(wire.getLast(), following(0x65, 7, 99)), "the new world's entity id");

    List<byte[]> legacy = new ArrayList<>();
    ClientDisplay old = new ClientDisplay(DisplayApiTests.dummyPlayer(), ProtocolDefinition.forVersion(47), legacy::add);
    enter(old, DisplayApiTests.packet(0x01, out -> out.writeInt(5)));
    old.playSound(SOUND);
    old.stopSound(null, null);
    require(legacy.isEmpty(), "a 1.8 client is sent no sound at itself and no stop");
    old.playSound(SOUND, X, Y, Z);
    require(legacy.size() == 1 && Arrays.equals(legacy.get(0), at(47, 0x29, 7)), "but a positioned one");
  }

  private static void enter(ClientDisplay display, byte[] joinGame) {
    display.beforeWrite(ConnectionState.PLAY, joinGame);
    display.afterWrite(ConnectionState.PLAY, joinGame);
  }

  /** A scripted 1.8 client over a 1.8 pair: the positioned sound arrives; at-player and stop send nothing. */
  private static void aLegacyClientHearsWhatItCan() throws Exception {
    try (NativeApiTests.Backend lobby = new NativeApiTests.Backend("lobby");
         NativeApiTests.Fixture proxy = new NativeApiTests.Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"))) {
      try (NativeApiTests.Client client = NativeApiTests.Client.join(proxy.port(), "listener")) {
        require(proxy.recorder.await(PlayerServerConnectedEvent.class, 1), "joined");
        require(client.await(packet -> NativeApiTests.id(packet) == 0x01), "Join Game");
        Player player = proxy.runtime.player("listener").orElseThrow();
        player.playSound(SOUND);
        player.stopSound(null, null);
        player.playSound(SOUND, X, Y, Z);
        player.sendMessage(Text.of("marker"));
        require(client.await(packet -> NativeApiTests.id(packet) == 0x02 && utf8(packet).contains("marker")), "the marker after the sounds");
        List<byte[]> sounds = client.received(packet -> NativeApiTests.id(packet) == 0x29);
        require(sounds.size() == 1 && Arrays.equals(sounds.get(0), at(47, 0x29, 7)), "exactly the positioned sound, in 1.8's form");
        require(client.received(packet -> utf8(packet).contains("StopSound")).isEmpty(), "and no stop of any kind");
      }
    }
  }

  /** A scripted 1.20.4 client: a sound following it (entity 7, from its Join Game), one at a position, and a stop. */
  private static void aModernClientHearsEverything() throws Exception {
    ProtocolDefinition p = ProtocolDefinition.forVersion(765);
    int configOut = p.id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_PLUGIN_MESSAGE);
    byte finishOut = (byte) p.id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_FINISH);
    byte finishIn = (byte) p.id(ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_FINISH);
    try (ServerSocket listener = new ServerSocket(0)) {
      ModLoaderTests.Mock backend = new ModLoaderTests.Mock(listener, "sound", new byte[0], configOut, finishOut, finishIn);
      DisplayApiTests.platform("sound-backend", backend);
      ConduitConfiguration configuration = new ConduitConfiguration(new InetSocketAddress("127.0.0.1", ModLoaderTests.reservePort()), 8192,
          ForwardingMode.NONE, Optional.empty(), List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", listener.getLocalPort()))),
          List.of("lobby"), List.of());
      try (MinecraftProxy proxy = new MinecraftProxy(configuration)) {
        DisplayApiTests.platform("sound-serve", () -> { try { proxy.serve(); } catch (Exception ignored) { } });
        try (Socket client = new Socket("127.0.0.1", proxy.port())) {
          client.setSoTimeout(15_000);
          InputStream in = client.getInputStream();
          OutputStream out = client.getOutputStream();
          MinecraftFrames.write(out, new Handshake(765, "localhost", 25565, 2).encode());
          MinecraftFrames.write(out, ModLoaderTests.loginStart());
          require(MinecraftFrames.read(in, 8192)[0] == 0x02, "Login Success");
          MinecraftFrames.write(out, new byte[] {0x03});
          ModLoaderTests.readConfiguration(in, finishOut);
          MinecraftFrames.write(out, new byte[] {finishIn});
          while (MinecraftFrames.read(in, 1 << 20)[0] != 0x29) { }
          Player player = proxy.runtime().player("playr").orElseThrow();
          player.playSound(SOUND);
          player.playSound(SOUND, X, Y, Z);
          player.stopSound(NAME, Sound.Source.MUSIC);
          require(Arrays.equals(next(in, 0x65), following(0x65, 7, 7)), "the sound follows entity 7, the id its Join Game gave");
          require(Arrays.equals(next(in, 0x66), at(765, 0x66, 7)), "the positioned sound");
          require(Arrays.equals(next(in, 0x68), stop(0x68, 1, NAME)), "the stop");
        }
      }
    }
  }

  private static String utf8(byte[] packet) { return new String(packet, java.nio.charset.StandardCharsets.UTF_8); }

  static byte[] next(InputStream in, int id) throws IOException {
    while (true) {
      byte[] packet = MinecraftFrames.read(in, 1 << 20);
      if (packet[0] == (byte) id) return packet;
    }
  }

  static void expect(Optional<byte[]> actual, byte[] expected, String what) {
    require(actual.isPresent(), what + ": nothing was built");
    require(Arrays.equals(actual.get(), expected), what + ": expected " + HexFormat.of().formatHex(expected)
        + " got " + HexFormat.of().formatHex(actual.get()));
  }

  static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
