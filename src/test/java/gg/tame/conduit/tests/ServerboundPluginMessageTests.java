// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.ProtocolCatalog;
import gg.tame.conduit.protocol.ProtocolDefinition;

/**
 * Every release Conduit has a table for can have a plugin message written toward its backend.
 *
 * <p>Conduit writes one on the player's behalf -- the BungeeCord channel registration a hub plugin
 * needs, a plugin's message to the server -- and a table without the serverbound id makes that a
 * silent no-op. The 26.2 table had none, so DeluxeHub's server selector did nothing on 26.2 only.
 */
public final class ServerboundPluginMessageTests {
  public static void main(String[] arguments) throws Exception { run(); }

  public static void run() {
    everyTableCanWriteAPluginMessageToTheServer();
    theNewIdsAreThePublishedOnes();
    System.out.println("ServerboundPluginMessageTests OK");
  }

  private static void everyTableCanWriteAPluginMessageToTheServer() {
    for (int number : ProtocolCatalog.codecNumbers()) {
      ProtocolDefinition definition = ProtocolDefinition.forVersion(number);
      require(definition.defines(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_PLUGIN_MESSAGE),
          "protocol " + number + " has a serverbound Play plugin message");
      if (definition.hasConfiguration()) {
        require(definition.defines(ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_PLUGIN_MESSAGE),
            "protocol " + number + " has a serverbound Configuration plugin message");
      }
    }
  }

  /** From the minecraft.wiki packet lists for protocols 776 and 763. */
  private static void theNewIdsAreThePublishedOnes() {
    ProtocolDefinition definition = ProtocolDefinition.forVersion(776);
    require(definition.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_PLUGIN_MESSAGE) == 0x16,
        "26.2's serverbound Play plugin message is 0x16");
    require(definition.id(ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_PLUGIN_MESSAGE) == 0x02,
        "26.2's serverbound Configuration plugin message is 0x02");
    require(ProtocolDefinition.forVersion(763).id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_PLUGIN_MESSAGE) == 0x0D,
        "1.20.1's serverbound Play plugin message is 0x0D");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
