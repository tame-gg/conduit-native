// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol;

import gg.tame.conduit.api.event.proxy.ServerListPingEvent;
import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.protocol.text.ComponentCodec;
import gg.tame.conduit.text.TextCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Local server-list status implementation. */
public final class StatusResponder {
  private StatusResponder() { }
  public static byte[] response(ProtocolDefinition protocol, byte[] request, String description) throws IOException {
    return answer(protocol, request, json(Text.of(description), protocol.version().displayName(), protocol.version().number(),
        false, 0, 0, List.of(), Optional.empty(), protocol.version().number()));
  }
  /** The answer as the proxy and its plugins left it in {@code ping}. */
  public static byte[] response(ProtocolDefinition protocol, byte[] request, ServerListPingEvent ping) throws IOException {
    return response(protocol, request, ping, false);
  }
  /**
   * As above, saying the network prevents chat reports when {@code preventsChatReports}: the field
   * No Chat Reports clients read to mark a server safe in their list. Others ignore it.
   */
  public static byte[] response(ProtocolDefinition protocol, byte[] request, ServerListPingEvent ping,
                                boolean preventsChatReports) throws IOException {
    String json = json(ping.description(), ping.versionName(), ping.versionProtocol(),
        ping.playersHidden(), ping.maxPlayers(), ping.onlinePlayers(), ping.samplePlayers(), ping.favicon(), ping.protocolVersion());
    if (preventsChatReports) json = json.substring(0, json.length() - 1) + ",\"preventsChatReports\":true}";
    if (ping.modInfo().isPresent()) json = json.substring(0, json.length() - 1) + modInfo(ping.modInfo().get()) + "}";
    return answer(protocol, request, json);
  }
  /** {@code ,"modinfo":{...}}: the shape public docs give for a 1.7-1.12 Forge server's answer, which its clients read. */
  private static String modInfo(gg.tame.conduit.api.server.ModInfo info) {
    StringBuilder json = new StringBuilder(",\"modinfo\":{\"type\":");
    ComponentCodec.quote(json, info.type());
    json.append(",\"modList\":[");
    for (int index = 0; index < info.mods().size(); index++) {
      if (index > 0) json.append(',');
      json.append("{\"modid\":");
      ComponentCodec.quote(json, info.mods().get(index).id());
      json.append(",\"version\":");
      ComponentCodec.quote(json, info.mods().get(index).version());
      json.append('}');
    }
    return json.append("]}").toString();
  }
  /** Throws unless {@code request} is a status request, so nothing is asked of plugins for a malformed one. */
  public static void checkRequest(ProtocolDefinition protocol, byte[] request) throws IOException {
    if (!protocol.is(ConnectionState.STATUS, PacketDirection.CLIENT_TO_SERVER, packetId(request), PacketKind.STATUS_REQUEST)) throw new IOException("expected status request");
  }
  private static byte[] answer(ProtocolDefinition protocol, byte[] request, String json) throws IOException {
    checkRequest(protocol, request);
    return packet(protocol.id(ConnectionState.STATUS, PacketDirection.SERVER_TO_CLIENT, PacketKind.STATUS_REQUEST), json.getBytes(java.nio.charset.StandardCharsets.UTF_8), true);
  }
  private static String json(Text description, String versionName, int versionProtocol, boolean hidePlayers, int max,
                             int online, List<ServerListPingEvent.SamplePlayer> sample, Optional<String> favicon,
                             int clientProtocol) {
    // Every string goes through the one escaper that handles control characters. The old one escaped
    // quotes and backslashes only, so a MOTD with a line break in it was JSON no client could read.
    StringBuilder json = new StringBuilder("{\"version\":{\"name\":");
    ComponentCodec.quote(json, versionName);
    json.append(",\"protocol\":").append(versionProtocol).append('}');
    // With no "players" at all the client shows "???" where the counts go.
    if (!hidePlayers) {
      json.append(",\"players\":{\"max\":").append(max).append(",\"online\":").append(online);
      if (!sample.isEmpty()) {
        json.append(",\"sample\":[");
        for (int index = 0; index < sample.size(); index++) {
          if (index > 0) json.append(',');
          json.append("{\"name\":");
          ComponentCodec.quote(json, sample.get(index).name());
          json.append(",\"id\":\"").append(sample.get(index).uniqueId()).append("\"}");
        }
        json.append(']');
      }
      json.append('}');
    }
    // In the pinging client's own release, which may be one Conduit has no table for: a 1.12 client
    // gets RGB colours as the nearest named ones, and its hover text under the key it reads.
    json.append(",\"description\":").append(TextCodec.toJson(description, clientProtocol));
    if (favicon.isPresent()) {
      json.append(",\"favicon\":");
      ComponentCodec.quote(json, favicon.get());
    }
    return json.append('}').toString();
  }
  public static byte[] pong(ProtocolDefinition protocol, byte[] request) throws IOException {
    if (packetId(request) != protocol.id(ConnectionState.STATUS, PacketDirection.CLIENT_TO_SERVER, PacketKind.STATUS_PING)) throw new IOException("expected status ping");
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(request))) {
      MinecraftInput.varInt(input); long nonce = input.readLong(); return packet(protocol.id(ConnectionState.STATUS, PacketDirection.SERVER_TO_CLIENT, PacketKind.STATUS_PING), java.nio.ByteBuffer.allocate(8).putLong(nonce).array(), false);
    }
  }
  private static int packetId(byte[] packet) throws IOException { return MinecraftInput.varInt(new DataInputStream(new ByteArrayInputStream(packet))); }
  private static byte[] packet(int id, byte[] data, boolean string) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(); try (DataOutputStream output = new DataOutputStream(bytes)) { MinecraftOutput.varInt(output, id); if (string) MinecraftOutput.string(output, new String(data, java.nio.charset.StandardCharsets.UTF_8)); else output.write(data); } return bytes.toByteArray();
  }
}
