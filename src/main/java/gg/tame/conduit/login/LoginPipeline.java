package gg.tame.conduit.login;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.ProtocolSession;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

/** State-aware packet observer. It does not conflate client authentication with backend forwarding. */
public final class LoginPipeline {
  private final ProtocolSession session;
  private final ProtocolDefinition protocol;
  private PlayerProfile player;
  public LoginPipeline(ProtocolSession session, ProtocolDefinition protocol) { this.session = session; this.protocol = protocol; }
  public PlayerProfile player() { if (player == null) throw new IllegalStateException("Login Start has not arrived"); return player; }
  public void observe(PacketDirection direction, byte[] packet) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      int id = MinecraftInput.varInt(input);
      byte[] body = input.readAllBytes();
      if (direction == PacketDirection.CLIENT_TO_SERVER && session.state() == ConnectionState.LOGIN && protocol.is(ConnectionState.LOGIN, direction, id, PacketKind.LOGIN_START)) player = LoginStart.decode(body).unverifiedProfile();
      else if (direction == PacketDirection.SERVER_TO_CLIENT && session.state() == ConnectionState.LOGIN && protocol.is(ConnectionState.LOGIN, direction, id, PacketKind.LOGIN_SUCCESS)) session.beginConfiguration();
      else if (direction == PacketDirection.SERVER_TO_CLIENT && session.state() == ConnectionState.CONFIGURATION && protocol.is(ConnectionState.CONFIGURATION, direction, id, PacketKind.CONFIGURATION_FINISH)) session.beginPlay();
    }
  }
}
