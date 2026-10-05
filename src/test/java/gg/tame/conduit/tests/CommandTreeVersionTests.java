// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.command.CommandGraphs;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.ProtocolDefinition;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.List;

/**
 * Every release with a command tree, 1.13 to the newest codec, gets the proxy's commands merged
 * into a backend's tree: the backend's own command kept first, the proxy's after it, once each.
 * A tree the client is sent without them shows /conduit, /server and /hub in red.
 */
public final class CommandTreeVersionTests {
  private CommandTreeVersionTests() {}

  public static void main(String[] arguments) throws Exception { run(); }

  public static void run() throws Exception {
    int checked = 0;
    for (ProtocolDefinition protocol : ProtocolDefinition.all().values()) {
      if (!protocol.capabilities().commandTree()) continue;
      String release = protocol.version().displayName();
      require(protocol.defines(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DECLARE_COMMANDS),
          release + " defines Declare Commands");
      byte[] backend = backendTree(protocol);
      byte[] merged = CommandGraphs.mergeProxyCommands(protocol, backend, List.of("lobby", "dev"));
      require(merged.length > backend.length, release + " gains the proxy's nodes");
      List<String> roots = CommandGraphs.rootNames(protocol, merged);
      // A release whose parser registry is not tabled cannot be read back, only merged into.
      if (roots != null) {
        require(roots.get(0).equals("spawn"), release + " keeps the backend's command first, got " + roots);
        for (String name : List.of("conduit", "server", "hub", "lobby", "dev")) {
          require(roots.stream().filter(name::equals).count() == 1, release + " declares /" + name + " once, got " + roots);
        }
      }
      checked++;
    }
    require(checked >= 30, "every command-tree release was checked, only " + checked);
    System.out.println("CommandTreeVersionTests passed (" + checked + " releases)");
  }

  /** Declare Commands with a root and one executable literal, /spawn. */
  private static byte[] backendTree(ProtocolDefinition protocol) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(out, protocol.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DECLARE_COMMANDS));
      MinecraftOutput.varInt(out, 2);
      out.writeByte(0x00);
      MinecraftOutput.varInt(out, 1);
      MinecraftOutput.varInt(out, 1);
      out.writeByte(0x01 | 0x04);
      MinecraftOutput.varInt(out, 0);
      MinecraftOutput.string(out, "spawn");
      MinecraftOutput.varInt(out, 0);
    }
    return bytes.toByteArray();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
