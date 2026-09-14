package gg.tame.conduit.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Local server-list status implementation. */
public final class StatusResponder {
  private StatusResponder() { }
  public static byte[] response(ProtocolDefinition protocol, byte[] request, String description) throws IOException {
    return response(protocol, request, description, protocol.version().displayName(), protocol.version().number());
  }
  public static byte[] response(ProtocolDefinition protocol, byte[] request, String description,
                                String versionName, int versionProtocol) throws IOException {
    int id = packetId(request);
    if (!protocol.is(ConnectionState.STATUS, PacketDirection.CLIENT_TO_SERVER, id, PacketKind.STATUS_REQUEST)) throw new IOException("expected status request");
    String json = "{\"version\":{\"name\":\"" + jsonEscape(versionName) + "\",\"protocol\":" + versionProtocol
        + "},\"players\":{\"max\":0,\"online\":0},\"description\":{\"text\":\"" + jsonEscape(description) + "\"}}";
    return packet(protocol.id(ConnectionState.STATUS, PacketDirection.SERVER_TO_CLIENT, PacketKind.STATUS_REQUEST), json.getBytes(java.nio.charset.StandardCharsets.UTF_8), true);
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
  private static String jsonEscape(String text) { return text.replace("\\", "\\\\").replace("\"", "\\\""); }
}
