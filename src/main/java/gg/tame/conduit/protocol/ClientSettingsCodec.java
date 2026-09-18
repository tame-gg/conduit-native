// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol;

import gg.tame.conduit.api.player.ClientSettings;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Locale;

/**
 * Reads the body of a client's Client Information packet in that client's own layout: language,
 * view distance, chat mode and chat colours, then by release the skin parts (a 1.7 client sends a
 * difficulty and whether it shows its cape instead), the main hand (1.9), text filtering (1.17),
 * server listing (1.18) and particles (1.21.2). The chat mode is a byte before 1.9 and a VarInt after.
 */
public final class ClientSettingsCodec {
  private static final int V1_8 = 47;
  private static final int V1_9 = 107;
  private static final int V1_17 = 755;
  private static final int V1_18 = 757;
  private static final int V1_21_2 = 768;
  private ClientSettingsCodec() { }

  public static ClientSettings decode(int protocol, byte[] body) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
      Locale locale = Locale.forLanguageTag(MinecraftInput.string(input, 64).replace('_', '-'));
      int viewDistance = input.readByte();
      int chatMode = protocol >= V1_9 ? MinecraftInput.varInt(input) : input.readByte();
      boolean colours = input.readBoolean();
      int skin;
      if (protocol < V1_8) {
        input.readByte();                                   // difficulty
        skin = input.readBoolean() ? 0x7F : 0x7E;
      } else {
        skin = input.readUnsignedByte();
      }
      ClientSettings.MainHand hand = protocol >= V1_9 && MinecraftInput.varInt(input) == 0
          ? ClientSettings.MainHand.LEFT : ClientSettings.MainHand.RIGHT;
      boolean filtering = protocol >= V1_17 && input.readBoolean();
      boolean listing = protocol < V1_18 || input.readBoolean();
      ClientSettings.Particles particles = protocol < V1_21_2 ? ClientSettings.Particles.ALL : switch (MinecraftInput.varInt(input)) {
        case 1 -> ClientSettings.Particles.DECREASED;
        case 2 -> ClientSettings.Particles.MINIMAL;
        default -> ClientSettings.Particles.ALL;
      };
      ClientSettings.ChatMode mode = switch (chatMode) {
        case 1 -> ClientSettings.ChatMode.COMMANDS_ONLY;
        case 2 -> ClientSettings.ChatMode.HIDDEN;
        default -> ClientSettings.ChatMode.FULL;
      };
      return new ClientSettings(locale, viewDistance, mode, colours, skin, hand, filtering, listing, particles);
    }
  }
}
