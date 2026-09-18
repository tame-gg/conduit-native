// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.api.server.ServerAvailability;
import gg.tame.conduit.api.server.ServerStatus;
import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.api.text.TextColor;
import gg.tame.conduit.protocol.BackendStatusProbe;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.text.TextCodec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.time.Instant;

final class Phase10Tests {
  static void run() throws Exception {
    textPlainAndJson();
    textNetworkPacket();
    statusParsing();
    serverStatusModel();
  }

  private static void textPlainAndJson() {
    Text text = Text.of("Hello ").color(TextColor.AQUA).bold()
        .append(Text.of("World").color(TextColor.GREEN).clickRun("/server lobby").hover("Go to lobby"));
    require(text.plain().equals("Hello World"), "plain flatten");
    String json = TextCodec.toJson(text);
    require(json.contains("\"color\":\"aqua\""), "json color");
    require(json.contains("\"bold\":true"), "json bold");
    require(json.contains("run_command"), "json click");
    require(json.contains("/server lobby"), "json click value");
    require(json.contains("show_text"), "json hover");
  }

  private static void textNetworkPacket() throws Exception {
    ProtocolDefinition protocol = ProtocolDefinition.forVersion(765);
    Text text = Text.of("Conduit").color(TextColor.AQUA).bold();
    byte[] packet = PlayPackets.systemChat(protocol, text);
    require(packet.length > 4, "system chat packet");
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      TextCodec.write(output, text, true);
    }
    require(bytes.size() > 8, "nbt component written");
  }

  private static void statusParsing() {
    var ad = BackendStatusProbe.parse("{\"version\":{\"name\":\"Paper 1.20.4\",\"protocol\":765},\"players\":{\"online\":3,\"max\":100}}").orElseThrow();
    require(ad.protocol() == 765, "protocol");
    require(ad.name().equals("Paper 1.20.4"), "name");
    require(ad.onlinePlayers() == 3, "online");
    require(ad.maxPlayers() == 100, "max");
  }

  private static void serverStatusModel() {
    ServerStatus online = ServerStatus.online("lobby", 765, "Paper 1.20.4", 3, 100, 12, Instant.now());
    require(online.availability() == ServerAvailability.ONLINE, "online");
    require(online.online(), "online flag");
    require(online.onlinePlayers().orElse(-1) == 3, "players");
    ServerStatus offline = ServerStatus.offline("survival", Instant.now());
    require(offline.availability() == ServerAvailability.OFFLINE, "offline");
    require(!offline.online(), "offline flag");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
