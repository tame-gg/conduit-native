package gg.tame.conduit.protocol;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Status ping used to learn a backend's advertised protocol. Not a translation layer. */
public final class BackendStatusProbe {
  private static final Pattern PROTOCOL = Pattern.compile("\"protocol\"\\s*:\\s*(\\d+)");
  private static final Pattern NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
  private BackendStatusProbe() {}
  public record Advertisement(int protocol, String name) {}
  public static Optional<Advertisement> probe(InetSocketAddress address, int timeoutMillis) {
    try (Socket socket = new Socket()) {
      socket.connect(address, timeoutMillis);
      socket.setSoTimeout(timeoutMillis);
      Handshake handshake = new Handshake(-1, address.getHostString(), address.getPort(), 1);
      MinecraftFrames.write(socket.getOutputStream(), handshake.encode());
      MinecraftFrames.write(socket.getOutputStream(), new byte[] {0});
      byte[] response = MinecraftFrames.read(socket.getInputStream(), 32767);
      int id = PlayPackets.packetId(response);
      if (id != 0) return Optional.empty();
      String json = MinecraftInput.string(new DataInputStream(new ByteArrayInputStream(Arrays.copyOfRange(response, 1, response.length))), 32767);
      return parse(json);
    } catch (IOException exception) {
      return Optional.empty();
    }
  }
  public static Optional<Advertisement> parse(String json) {
    if (json == null || json.isBlank()) return Optional.empty();
    Matcher protocol = PROTOCOL.matcher(json);
    if (!protocol.find()) return Optional.empty();
    String name = "unknown";
    Matcher named = NAME.matcher(json);
    if (named.find()) name = named.group(1);
    return Optional.of(new Advertisement(Integer.parseInt(protocol.group(1)), name));
  }
  public static String describe(Advertisement advertisement) {
    TranslationSupport support = ProtocolCompatibility.between(advertisement.protocol(), advertisement.protocol());
    String codec = ProtocolDefinition.hasCodec(advertisement.protocol()) ? "DIRECT codec" : "no codec yet";
    return advertisement.name() + " protocol " + advertisement.protocol() + " (" + ProtocolVersion.display(advertisement.protocol()) + ", " + codec + ", translation=" + support + ")";
  }
}
