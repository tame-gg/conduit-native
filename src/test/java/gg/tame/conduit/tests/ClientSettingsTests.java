// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.NativeApiTests.packet;
import static gg.tame.conduit.tests.NativeApiTests.require;
import static gg.tame.conduit.tests.NativeApiTests.waitFor;

import gg.tame.conduit.api.event.player.PlayerServerConnectedEvent;
import gg.tame.conduit.api.player.ClientSettings;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.protocol.ClientSettingsCodec;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.tests.NativeApiTests.Backend;
import gg.tame.conduit.tests.NativeApiTests.Client;
import gg.tame.conduit.tests.NativeApiTests.Fixture;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * The client's settings, read in its own release's layout. Only the language was read, so plugins
 * were told the vanilla defaults for everything else a client says about itself -- its view
 * distance, chat mode, skin parts and main hand among them.
 */
public final class ClientSettingsTests {
  public static void main(String[] arguments) throws Exception { run(); }

  public static void run() throws Exception {
    everyReleaseIsReadInItsOwnLayout();
    aClientsSettingsReachThePlayer();
    System.out.println("ClientSettingsTests OK");
  }

  private interface Body { void write(DataOutputStream out) throws IOException; }

  private static byte[] body(Body body) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) { body.write(out); }
    return bytes.toByteArray();
  }

  private static void everyReleaseIsReadInItsOwnLayout() throws Exception {
    // 1.7: a byte chat mode, a difficulty and a cape flag in place of skin parts.
    ClientSettings v17 = ClientSettingsCodec.decode(5, body(out -> {
      MinecraftOutput.string(out, "fr_fr"); out.writeByte(6); out.writeByte(1); out.writeBoolean(false); out.writeByte(2); out.writeBoolean(false);
    }));
    require(v17.locale().equals(Locale.forLanguageTag("fr-FR")) && v17.viewDistance() == 6 && v17.chatMode() == ClientSettings.ChatMode.COMMANDS_ONLY
        && !v17.chatColors() && v17.skinParts() == 0x7E && v17.mainHand() == ClientSettings.MainHand.RIGHT, "1.7, got " + v17);
    // 1.8: skin parts, still a byte chat mode, no main hand.
    ClientSettings v18 = ClientSettingsCodec.decode(47, body(out -> {
      MinecraftOutput.string(out, "de_de"); out.writeByte(8); out.writeByte(2); out.writeBoolean(true); out.writeByte(0x41);
    }));
    require(v18.viewDistance() == 8 && v18.chatMode() == ClientSettings.ChatMode.HIDDEN && v18.skinParts() == 0x41
        && v18.mainHand() == ClientSettings.MainHand.RIGHT && v18.serverListing() && !v18.textFiltering(), "1.8, got " + v18);
    // 1.12.2: a VarInt chat mode and the main hand.
    ClientSettings v112 = ClientSettingsCodec.decode(340, body(out -> {
      MinecraftOutput.string(out, "en_gb"); out.writeByte(10); MinecraftOutput.varInt(out, 0); out.writeBoolean(true); out.writeByte(0x7F);
      MinecraftOutput.varInt(out, 0);
    }));
    require(v112.mainHand() == ClientSettings.MainHand.LEFT && v112.chatMode() == ClientSettings.ChatMode.FULL, "1.12.2, got " + v112);
    // 1.17: text filtering; 1.20.4: server listing too; 1.21.2: particles as well.
    ClientSettings v117 = ClientSettingsCodec.decode(755, body(out -> {
      MinecraftOutput.string(out, "en_us"); out.writeByte(12); MinecraftOutput.varInt(out, 0); out.writeBoolean(true); out.writeByte(0x7F);
      MinecraftOutput.varInt(out, 1); out.writeBoolean(true);
    }));
    require(v117.textFiltering() && v117.serverListing(), "1.17, got " + v117);
    ClientSettings v1204 = ClientSettingsCodec.decode(765, body(out -> {
      MinecraftOutput.string(out, "en_us"); out.writeByte(12); MinecraftOutput.varInt(out, 0); out.writeBoolean(true); out.writeByte(0x7F);
      MinecraftOutput.varInt(out, 1); out.writeBoolean(false); out.writeBoolean(false);
    }));
    require(!v1204.serverListing() && v1204.particles() == ClientSettings.Particles.ALL, "1.20.4, got " + v1204);
    ClientSettings v1212 = ClientSettingsCodec.decode(768, body(out -> {
      MinecraftOutput.string(out, "ja_jp"); out.writeByte(32); MinecraftOutput.varInt(out, 0); out.writeBoolean(true); out.writeByte(0x7F);
      MinecraftOutput.varInt(out, 1); out.writeBoolean(false); out.writeBoolean(true); MinecraftOutput.varInt(out, 2);
    }));
    require(v1212.viewDistance() == 32 && v1212.particles() == ClientSettings.Particles.MINIMAL && v1212.locale().equals(Locale.JAPAN),
        "1.21.2, got " + v1212);
  }

  /** A scripted 1.8 client sends its settings in the game; the native player then carries all of them. */
  private static void aClientsSettingsReachThePlayer() throws Exception {
    try (Backend lobby = new Backend("lobby"); Fixture proxy = new Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"));
         Client client = Client.join(proxy.port(), "Settler")) {
      require(proxy.recorder.await(PlayerServerConnectedEvent.class, 1), "joined");
      Player player = proxy.runtime.player("Settler").orElseThrow();
      require(player.settings().isEmpty() && player.locale().isEmpty(), "nothing is known before the client says");
      // The port the client connected from, which Velocity's getRemoteAddress used to report as 0.
      require(player.remoteSocketAddress().getPort() > 0 && player.remoteSocketAddress().getAddress().isLoopbackAddress(),
          "the client's own port is known, got " + player.remoteSocketAddress());
      client.send(packet(0x15, out -> {
        MinecraftOutput.string(out, "nl_nl"); out.writeByte(4); out.writeByte(1); out.writeBoolean(false); out.writeByte(0x03);
      }));
      require(waitFor(() -> player.settings().isPresent(), 5_000), "the settings arrived");
      ClientSettings settings = player.settings().orElseThrow();
      require(settings.locale().equals(Locale.forLanguageTag("nl-NL")) && settings.viewDistance() == 4
          && settings.chatMode() == ClientSettings.ChatMode.COMMANDS_ONLY && !settings.chatColors() && settings.skinParts() == 0x03,
          "the player carries what the client said, got " + settings);
      require(player.locale().equals(java.util.Optional.of(Locale.forLanguageTag("nl-NL"))), "and its language");
      require(lobby.await(p -> NativeApiTests.id(p) == 0x15), "the settings still reach the backend");
    }
  }
}
