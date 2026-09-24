// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.NativeApiTests.packet;
import static gg.tame.conduit.tests.NativeApiTests.waitFor;

import gg.tame.conduit.api.event.player.PlayerModInfoEvent;
import gg.tame.conduit.api.event.proxy.ServerListPingEvent;
import gg.tame.conduit.api.server.ModInfo;
import gg.tame.conduit.api.server.ServerStatus;
import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.config.AuthenticationSettings;
import gg.tame.conduit.config.ForwardingMode;
import gg.tame.conduit.login.LoginStart;
import gg.tame.conduit.modded.ForgeModList;
import gg.tame.conduit.protocol.BackendStatusProbe;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.StatusResponder;
import gg.tame.conduit.protocol.text.ComponentCodec;
import gg.tame.conduit.tests.LoginFlowTests.Proxy;
import gg.tame.conduit.tests.LoginPluginMessageTests.QueryBackend;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Forge mod lists: a 1.13+ client's, read from its login answer, and a Forge server's, read from its
 * status answer; and the mod info a plugin puts in the proxy's own status answer.
 *
 * <p>The client fixture and the NeoForge status fixture were captured off a real NeoForge 20.2.93
 * client and server (Minecraft 1.20.2). The Forge 1.13-1.20.1 and legacy shapes follow public docs:
 * no such client or server was on hand.
 */
public final class ModInfoTests {
  private ModInfoTests() {}

  public static void main(String[] a) throws Exception { run(); }

  public static void run() throws Exception {
    capturedClientModList();
    documentedClientModList();
    aClientModListReachesPlugins();
    backendStatusModInfo();
    pluginModInfoInTheProxyAnswer();
  }

  /**
   * The NeoForge 20.2.93 client's answer to Login Plugin Request 0x01, the server's mod list. The
   * frame was 212 bytes; the capture kept its first 96. Those hold the whole mod list, and the rest
   * (channels and registries) is zero-filled to the length the frame declared.
   */
  private static final String CAPTURED_MOD_LIST_REPLY =
      "d2010201010110666d6c3a6c6f67696e777261707065720d666d6c3a68616e647368616b65ad010202096d696e656372616674"
      + "086e656f666f726765070e6e656f666f7267653a73706c697403312e3110666d6c3a6c6f67696e777261707065";
  /** The same client's answer to Login Plugin Request 0x02: an Acknowledge, whole. */
  private static final String CAPTURED_ACKNOWLEDGE = "250202010210666d6c3a6c6f67696e777261707065720d666d6c3a68616e647368616b650163";
  /** A real NeoForge 20.2.93 server's status answer, whole: its mods are only in the packed "d". */
  private static final String CAPTURED_NEOFORGE_STATUS =
      "{\"neoForgeData\":{\"channels\":[],\"mods\":[],\"truncated\":false,\"fmlNetworkVersion\":3,\"d\":\"a\\u0000\\u0000"
      + "\u0804\u3424\u734b\u3656\u2e4c\u1998\u033a\u2e31\u6064\u48b8\u2851\u26e7\u6cae\u5a59\u3a39\u7265\u0c08\u3135\u099a"
      + "\u2080\u6cae\u5a59\u3a39\u7265\u0c08\u3135\u099a@\u2dc1\u1bd9\u37b3\u6772\u06ca\u3904\u2aca\u0730\u2d8e\u5d1a\u1881"
      + "\u312e\u1802\u25d0\u132b\u35f7\u4dee\u5d1c\u3734\u0367\u5c62\u00c0\\u0000\"},\"description\":{\"text\":\"A Minecraft Server\"},"
      + "\"players\":{\"max\":20,\"online\":0},\"version\":{\"name\":\"1.20.2\",\"protocol\":764}}";

  private static void capturedClientModList() throws Exception {
    byte[] reply = unframe(CAPTURED_MOD_LIST_REPLY, true);
    require(ForgeModList.clientMods(reply).equals(List.of("minecraft", "neoforge")), "the captured client names its two mods, got " + ForgeModList.clientMods(reply));
    require(ForgeModList.clientMods(unframe(CAPTURED_ACKNOWLEDGE, false)) == null, "an Acknowledge is no mod list");
    byte[] refused = reply.clone();
    refused[2] = 0;
    require(ForgeModList.clientMods(refused) == null, "an answer the client did not understand is no mod list");
    require(ForgeModList.clientMods(Arrays.copyOf(reply, 60)) == null, "a cut-off answer is none either");
  }

  /** Forge 1.13-1.20.1 as documented: the inner channel, then the length-prefixed ModListReply. */
  private static void documentedClientModList() throws Exception {
    byte[] reply = packet(0x02, out -> {
      MinecraftOutput.varInt(out, 5);
      out.writeBoolean(true);
      MinecraftOutput.string(out, "fml:handshake");
      byte[] inner = packet(2, list -> {
        MinecraftOutput.varInt(list, 2);
        MinecraftOutput.string(list, "forge");
        MinecraftOutput.string(list, "jei");
        MinecraftOutput.varInt(list, 0);
        MinecraftOutput.varInt(list, 0);
      });
      MinecraftOutput.varInt(out, inner.length);
      out.write(inner);
    });
    require(ForgeModList.clientMods(reply).equals(List.of("forge", "jei")), "the documented wrapping reads too");
  }

  /** Through a real proxy: the client's answer reaches the backend and its mods reach plugins once it joins. */
  private static void aClientModListReachesPlugins() throws Exception {
    try (QueryBackend lobby = new QueryBackend(false, List.of("fml:loginwrapper"));
         Proxy proxy = new Proxy(List.of(lobby.server()), AuthenticationSettings.offline(), null, null, ForwardingMode.NONE, Optional.empty());
         Socket client = new Socket("127.0.0.1", proxy.port())) {
      client.setSoTimeout(20_000);
      var out = client.getOutputStream();
      MinecraftFrames.write(out, new Handshake(765, "localhost\0FML3", 25565, 2).encode());
      UUID uuid = LoginStart.offlineUuid("Modded");
      MinecraftFrames.write(out, packet(0, body -> {
        MinecraftOutput.string(body, "Modded"); body.writeLong(uuid.getMostSignificantBits()); body.writeLong(uuid.getLeastSignificantBits());
      }));
      byte[] captured = unframe(CAPTURED_MOD_LIST_REPLY, true);
      boolean login = true;
      while (!proxy.recorder.of(PlayerModInfoEvent.class).iterator().hasNext()) {
        byte[] frame = MinecraftFrames.read(client.getInputStream(), 1 << 21);
        if (login && frame[0] == 0x04) {
          int messageId = MinecraftInput.varInt(new DataInputStream(new ByteArrayInputStream(frame, 1, frame.length - 1)));
          byte[] answer = packet(0x02, body -> { MinecraftOutput.varInt(body, messageId); body.write(captured, 2, captured.length - 2); });
          MinecraftFrames.write(out, answer);
        } else if (login && frame[0] == 0x02) {
          login = false;
          MinecraftFrames.write(out, new byte[] {0x03});
        } else if (!login && frame[0] == 0x02 && frame.length == 1) {
          MinecraftFrames.write(out, new byte[] {0x02});
          require(waitFor(() -> !proxy.recorder.of(PlayerModInfoEvent.class).isEmpty(), 10_000), "the mod list reached plugins");
        }
      }
      require(waitFor(() -> lobby.answers.size() == 1, 10_000) && lobby.answers.getFirst().startsWith("1:true:"), "the backend got the client's answer: " + lobby.answers);
      PlayerModInfoEvent event = proxy.recorder.of(PlayerModInfoEvent.class).getFirst();
      require(event.player().username().equals("Modded"), "for the player who sent it");
      require(event.modInfo().equals(new ModInfo("FML3", List.of(new ModInfo.Mod("minecraft", ""), new ModInfo.Mod("neoforge", "")))),
          "an FML3 list of ids without versions, got " + event.modInfo());
    }
  }

  private static void backendStatusModInfo() throws Exception {
    ModInfo neo = pingWith(CAPTURED_NEOFORGE_STATUS).modInfo().orElseThrow(() -> new AssertionError("NeoForge's packed mod list"));
    require(neo.equals(new ModInfo("FML3", List.of(new ModInfo.Mod("minecraft", "1.20.2"), new ModInfo.Mod("neoforge", "ANY")))),
        "the real NeoForge answer unpacks, got " + neo);
    String version = "\"version\":{\"name\":\"1.12.2\",\"protocol\":340}";
    require(pingWith("{" + version + ",\"modinfo\":{\"type\":\"FML\",\"modList\":[{\"modid\":\"forge\",\"version\":\"14.23.5.2860\"}]}}").modInfo()
        .equals(Optional.of(new ModInfo("FML", List.of(new ModInfo.Mod("forge", "14.23.5.2860"))))), "a legacy modinfo reads");
    require(pingWith("{" + version + ",\"forgeData\":{\"channels\":[],\"mods\":[{\"modId\":\"forge\",\"modmarker\":\"ANY\"}],\"fmlNetworkVersion\":2}}").modInfo()
        .equals(Optional.of(new ModInfo("FML2", List.of(new ModInfo.Mod("forge", "ANY"))))), "an unpacked forgeData reads");
    ServerStatus vanilla = pingWith("{" + version + "}");
    require(vanilla.online() && vanilla.modInfo().isEmpty(), "a vanilla server has no mod info");
    require(pingWith("{" + version + ",\"forgeData\":{\"d\":\"\\u0005\\u0000\"}}").modInfo().isEmpty(), "a packed list shorter than it says is none");
  }

  /** A plugin's mod info goes out as modinfo; with none, nothing is added. */
  private static void pluginModInfoInTheProxyAnswer() throws Exception {
    ProtocolDefinition protocol = ProtocolDefinition.forVersion(340);
    ServerListPingEvent ping = new ServerListPingEvent(new InetSocketAddress("127.0.0.1", 1), Optional.empty(), 25565, 340,
        Text.of("hi"), 10, 0, List.of(), "Conduit", 340, Optional.empty());
    require(!statusJson(protocol, ping).containsKey("modinfo"), "no mod info unless someone sets it");
    ModInfo info = new ModInfo("FML", List.of(new ModInfo.Mod("forge", "14.23.5.2860"), new ModInfo.Mod("jei", "4.16")));
    ping.setModInfo(Optional.of(info));
    Map<?, ?> json = statusJson(protocol, ping);
    require(ForgeModList.fromStatus(json).equals(Optional.of(info)), "the answer carries it as modinfo, got " + json.get("modinfo"));
  }

  private static Map<?, ?> statusJson(ProtocolDefinition protocol, ServerListPingEvent ping) throws Exception {
    byte[] response = StatusResponder.response(protocol, new byte[] {0}, ping, false);
    String json = MinecraftInput.string(new DataInputStream(new ByteArrayInputStream(response, 1, response.length - 1)), 1 << 20);
    return (Map<?, ?>) ComponentCodec.parseJson(json);
  }

  /** A one-shot backend answering a status request with {@code json}. */
  private static ServerStatus pingWith(String json) throws Exception {
    try (ServerSocket listener = new ServerSocket(0)) {
      Thread backend = Thread.ofPlatform().daemon().start(() -> {
        try (Socket socket = listener.accept()) {
          MinecraftFrames.read(socket.getInputStream(), 4096);
          MinecraftFrames.read(socket.getInputStream(), 4096);
          MinecraftFrames.write(socket.getOutputStream(), packet(0, body -> MinecraftOutput.string(body, json)));
        } catch (Exception ignored) { }
      });
      ServerStatus status = BackendStatusProbe.ping("forge", new InetSocketAddress("127.0.0.1", listener.getLocalPort()), null, -1, 5_000)
          .get(10, TimeUnit.SECONDS);
      backend.join(5_000);
      return status;
    }
  }

  /** A captured frame without its length prefix; {@code pad} zero-fills a truncated capture to the declared length. */
  private static byte[] unframe(String hex, boolean pad) throws Exception {
    byte[] bytes = HexFormat.of().parseHex(hex);
    DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
    int length = MinecraftInput.varInt(input);
    byte[] body = input.readAllBytes();
    return pad ? Arrays.copyOf(body, length) : body;
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
