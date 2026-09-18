// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.auth.Authenticators;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.crypto.RsaKeys;
import gg.tame.conduit.network.MinecraftProxy;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.ProtocolDefinition;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Adventure sounds from a Velocity plugin, end to end: a plugin compiled against the real velocity-api
 * plays {@link SoundApiTests#SOUND}'s twin at a position, at the player and with
 * {@code Sound.Emitter.self()}, and stops sounds. A scripted 1.20.4 client gets all of it; a scripted
 * 1.12.2 client gets the positioned sound and the stops through MC|StopSound, and nothing for a sound
 * at the player, which it could only be sent by registry id.
 */
public final class VelocitySoundTests {
  public static void main(String[] a) throws Exception { run(); }

  private static final String PLUGIN = """
      package vsound;
      import com.velocitypowered.api.command.SimpleCommand;
      import com.velocitypowered.api.event.Subscribe;
      import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
      import com.velocitypowered.api.plugin.Plugin;
      import com.velocitypowered.api.proxy.Player;
      import com.velocitypowered.api.proxy.ProxyServer;
      import javax.inject.Inject;
      import net.kyori.adventure.key.Key;
      import net.kyori.adventure.sound.Sound;
      import net.kyori.adventure.sound.SoundStop;

      @Plugin(id = "vsound", name = "VSound", version = "1.0")
      public final class VSound {
      """ + VelocityCompatTests.SIGNAL_METHOD + """
        static final Sound SOUND = Sound.sound().type(Key.key("entity.experience_orb.pickup")).source(Sound.Source.PLAYER)
            .volume(0.5f).pitch(1.5f).seed(42).build();
        private final ProxyServer proxy;
        @Inject public VSound(ProxyServer proxy) { this.proxy = proxy; }

        @Subscribe
        public void init(ProxyInitializeEvent event) {
          proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("vsound").plugin(this).build(), (SimpleCommand) invocation -> {
            Player player = (Player) invocation.source();
            switch (invocation.arguments()[0]) {
              case "at" -> player.playSound(SOUND, 1.5, 64, -2.25);
              case "self" -> {
                player.playSound(SOUND);
                player.playSound(SOUND, Sound.Emitter.self());
              }
              case "other" -> {
                try { player.playSound(SOUND, new Sound.Emitter() { }); signal("other:silent"); }
                catch (UnsupportedOperationException expected) { signal("other:" + expected.getMessage()); }
              }
              case "stop" -> {
                player.stopSound(SoundStop.namedOnSource(Key.key("entity.experience_orb.pickup"), Sound.Source.MUSIC));
                player.stopSound(SoundStop.all());
              }
              default -> { }
            }
            signal("vsound:" + invocation.arguments()[0]);
          });
          signal("vsound-ready");
        }
      }
      """;

  public static void run() throws Exception {
    VelocityCompatTests.installSignals();
    Path root = TempFiles.dir("velocity-sound");
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    VelocityCompatTests.jar(VelocityCompatTests.compile(root, "vsound.VSound", PLUGIN, List.of(), true), plugins.resolve("VSound.jar"), null);
    aModernClientHearsThePlugin(plugins);
    aLegacyClientHearsWhatItCan(plugins);
    System.out.println("VelocitySoundTests OK");
  }

  private static void aModernClientHearsThePlugin(Path plugins) throws Exception {
    ProtocolDefinition p = ProtocolDefinition.forVersion(765);
    int configOut = p.id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_PLUGIN_MESSAGE);
    byte finishOut = (byte) p.id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_FINISH);
    byte finishIn = (byte) p.id(ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_FINISH);
    int chatCommand = p.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT_COMMAND);
    try (ServerSocket listener = new ServerSocket(0)) {
      ModLoaderTests.Mock backend = new ModLoaderTests.Mock(listener, "sound", new byte[0], configOut, finishOut, finishIn);
      DisplayApiTests.platform("velocity-sound-backend", backend);
      ConduitConfiguration configuration = VelocityCompatTests.configuration(
          List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", listener.getLocalPort()))));
      try (MinecraftProxy proxy = new MinecraftProxy(configuration, Authenticators.create(configuration.authentication()), RsaKeys.generate(), plugins)) {
        DisplayApiTests.platform("velocity-sound-serve", () -> { try { proxy.serve(); } catch (IOException ignored) { } });
        VelocityCompatTests.awaitSignal("vsound-ready");
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
          MinecraftFrames.write(out, ModLoaderTests.command(chatCommand, "vsound at"));
          require(Arrays.equals(SoundApiTests.next(in, 0x66), SoundApiTests.at(765, 0x66, 7)), "Audience.playSound(sound, x, y, z)");
          MinecraftFrames.write(out, ModLoaderTests.command(chatCommand, "vsound self"));
          byte[] following = SoundApiTests.following(0x65, 7, 7);
          require(Arrays.equals(SoundApiTests.next(in, 0x65), following), "Audience.playSound(sound) follows the player, entity 7");
          require(Arrays.equals(SoundApiTests.next(in, 0x65), following), "and so does playSound(sound, Emitter.self())");
          MinecraftFrames.write(out, ModLoaderTests.command(chatCommand, "vsound other"));
          VelocityCompatTests.awaitSignal("other:Player.playSound(Sound, Emitter) with an emitter other than Sound.Emitter.self()"
              + " is not supported by Conduit's Velocity compatibility layer");
          MinecraftFrames.write(out, ModLoaderTests.command(chatCommand, "vsound stop"));
          require(Arrays.equals(SoundApiTests.next(in, 0x68), SoundApiTests.stop(0x68, 1, SoundApiTests.NAME)), "SoundStop.namedOnSource");
          require(Arrays.equals(SoundApiTests.next(in, 0x68), SoundApiTests.stop(0x68, null, null)), "SoundStop.all()");
        }
      }
    }
  }

  private static void aLegacyClientHearsWhatItCan(Path plugins) throws Exception {
    try (DisplayApiTests.Server1122 lobby = new DisplayApiTests.Server1122()) {
      ConduitConfiguration configuration = VelocityCompatTests.configuration(List.of(lobby.server("lobby")));
      try (MinecraftProxy proxy = new MinecraftProxy(configuration, Authenticators.create(configuration.authentication()), RsaKeys.generate(), plugins)) {
        DisplayApiTests.platform("velocity-sound-serve-old", () -> { try { proxy.serve(); } catch (IOException ignored) { } });
        VelocityCompatTests.awaitCount("vsound-ready", 2);
        try (VelocityDisplayTests.Client client = VelocityDisplayTests.Client.join(proxy.port(), "Old")) {
          client.await(packet -> NativeApiTests.id(packet) == 0x23, "Join Game");
          client.chat("/vsound self");
          VelocityCompatTests.awaitCount("vsound:self", 2);
          client.chat("/vsound at");
          client.await(packet -> Arrays.equals(packet, bytes(() -> SoundApiTests.at(340, 0x19, 7))), "the positioned sound in 1.12.2's form");
          client.chat("/vsound stop");
          client.await(packet -> Arrays.equals(packet, bytes(() -> SoundApiTests.stopChannel(0x18, "", ""))), "SoundStop.all() on MC|StopSound");
          require(client.received(packet -> Arrays.equals(packet, bytes(() -> SoundApiTests.stopChannel(0x18, SoundApiTests.NAME, "music")))).size() == 1,
              "the named stop on MC|StopSound");
          require(client.received(packet -> NativeApiTests.id(packet) == 0x19).size() == 1, "and no sound at all for the one at the player");
        }
      }
    }
  }

  private interface Build { byte[] bytes() throws IOException; }

  private static byte[] bytes(Build build) {
    try { return build.bytes(); } catch (IOException impossible) { throw new IllegalStateException(impossible); }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
