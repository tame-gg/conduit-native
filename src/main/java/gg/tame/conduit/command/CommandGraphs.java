package gg.tame.conduit.command;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolDefinition;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class CommandGraphs {
  private CommandGraphs() {}
  public static byte[] mergeProxyCommands(ProtocolDefinition protocol, byte[] packet, List<String> serverNames) throws IOException {
    int id = PlayPackets.packetId(packet);
    byte[] body = body(packet);
    CommandGraph graph = CommandGraph.decode(body);
    graph.addLiteralCommands(proxyLiterals(serverNames));
    byte[] encoded = graph.encode(id);
    CommandGraph.decode(body(encoded));
    return encoded;
  }
  public static byte[] proxyOnly(ProtocolDefinition protocol, List<String> serverNames) throws IOException {
    CommandGraph graph = CommandGraph.decode(rootOnly());
    graph.addLiteralCommands(proxyLiterals(serverNames));
    return graph.encode(protocol.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DECLARE_COMMANDS));
  }
  private static List<CommandGraph.LiteralCommand> proxyLiterals(List<String> serverNames) {
    List<String> sendChildren = new ArrayList<>();
    sendChildren.add("current");
    sendChildren.addAll(serverNames);
    return List.of(
        new CommandGraph.LiteralCommand("conduit", List.of()),
        new CommandGraph.LiteralCommand("server", serverNames),
        new CommandGraph.LiteralCommand("send", sendChildren));
  }
  private static byte[] rootOnly() throws IOException {
    var bytes = new java.io.ByteArrayOutputStream();
    try (var output = new java.io.DataOutputStream(bytes)) {
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, 1);
      output.writeByte(0);
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, 0);
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, 0);
    }
    return bytes.toByteArray();
  }
  private static byte[] body(byte[] packet) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      MinecraftInput.varInt(input);
      return input.readAllBytes();
    }
  }
}
