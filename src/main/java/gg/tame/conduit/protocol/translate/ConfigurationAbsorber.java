package gg.tame.conduit.protocol.translate;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.ProtocolTrace;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Optional;

/**
 * Absorbs a modern Configuration phase on behalf of a client that has no Configuration state
 * (e.g. 1.13). Never forwards Configuration packets to that client.
 *
 * <p>Packet IDs from public PrismarineJS minecraft-data for protocol 765 (1.20.4):
 * keep_alive 0x03, finish 0x02, ping 0x04 / pong 0x04, registry 0x05, feature_flags 0x08, tags 0x09.
 */
public final class ConfigurationAbsorber {
  private final ProtocolDefinition backend;
  private byte[] clientInformation;
  private boolean finished;

  public ConfigurationAbsorber(ProtocolDefinition backend) {
    this.backend = backend;
    if (!backend.hasConfiguration()) throw new IllegalArgumentException("backend has no configuration phase");
  }

  public void setClientInformation(byte[] playClientInformationBodyOrPacket) {
    this.clientInformation = playClientInformationBodyOrPacket;
  }

  public boolean finished() { return finished; }

  /**
   * Process one backend→proxy Configuration packet.
   * @return optional response to write to the backend (keepalive/pong/finish/settings)
   */
  public Optional<byte[]> onBackendPacket(byte[] packet) throws IOException {
    int id = PlayPackets.packetId(packet);
    ProtocolTrace.note("CONFIG ABSORB " + backend.version().number()
        + " 0x" + Integer.toHexString(id) + " len=" + packet.length);

    if (backend.is(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.CONFIGURATION_KEEP_ALIVE)) {
      long keepId;
      try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(packet)))) {
        keepId = input.readLong();
      }
      return Optional.of(encodeKeepAlive(keepId));
    }

    if (backend.is(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.CONFIGURATION_FINISH)) {
      finished = true;
      return Optional.of(PlayPackets.idOnly(
          backend.id(ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_FINISH)));
    }

    if (backend.is(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.CONFIGURATION_PLUGIN_MESSAGE)) {
      // Brand and other payloads: consume; brand is rewritten later in Play if needed.
      return Optional.empty();
    }

    if (backend.is(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.CONFIGURATION_DISCONNECT)) {
      throw new IOException("backend disconnected during configuration absorption");
    }

    // 765 ping (0x04) → pong (0x04); registry/tags/feature flags → consume.
    if (id == 0x04) {
      int pingId;
      try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(packet)))) {
        pingId = input.readInt();
      }
      return Optional.of(encodePong(pingId));
    }

    if (id == 0x05 || id == 0x08 || id == 0x09 || id == 0x06 || id == 0x07) {
      ProtocolTrace.note("CONFIG CONSUME registry/tags/features/resource-pack id=0x" + Integer.toHexString(id));
      return Optional.empty();
    }

    ProtocolTrace.note("CONFIG CONSUME unknown id=0x" + Integer.toHexString(id));
    return Optional.empty();
  }

  /** First packets Conduit should send after Login Ack when absorbing for a legacy client. */
  public Optional<byte[]> initialClientInformation() throws IOException {
    if (clientInformation == null) return Optional.empty();
    if (!backend.defines(ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_CLIENT_INFORMATION)) {
      return Optional.empty();
    }
    int id = backend.id(ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_CLIENT_INFORMATION);
    // If we stored a full play packet, strip id and re-frame; if body-only, wrap.
    byte[] body;
    try {
      if (PlayPackets.peekId(clientInformation) >= 0 && clientInformation.length > 1) {
        // Heuristic: treat as full packet when first varint is a known play settings id.
        body = PlayPackets.body(clientInformation);
      } else {
        body = clientInformation;
      }
    } catch (IOException exception) {
      body = clientInformation;
    }
    return Optional.of(PlayPackets.withId(id, body));
  }

  private byte[] encodeKeepAlive(long keepId) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, backend.id(ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_KEEP_ALIVE));
      output.writeLong(keepId);
    }
    return bytes.toByteArray();
  }

  private byte[] encodePong(int pingId) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, 0x04); // configuration pong on 765
      output.writeInt(pingId);
    }
    return bytes.toByteArray();
  }
}
