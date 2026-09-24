// SPDX-License-Identifier: GPL-3.0-or-later
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
  private volatile PlayerProfile player;
  /** Who logged in: the client's claim, or the session server's answer. {@link #player} differs only once a plugin replaced it. */
  private PlayerProfile account;
  public LoginPipeline(ProtocolSession session, ProtocolDefinition protocol) { this.session = session; this.protocol = protocol; }
  public PlayerProfile player() { if (player == null) throw new IllegalStateException("Login Start has not arrived"); return player; }
  public PlayerProfile account() { player(); return account; }
  public void adopt(PlayerProfile authenticated) {
    if (player == null) throw new IllegalStateException("Login Start has not arrived");
    this.player = this.account = AuthenticatedPlayerProfile.freeze(player, authenticated);
  }
  /** A GameProfileRequestEvent's replacement: what the player is from now on. The account stays what it was. */
  public void replace(PlayerProfile replacement) {
    player();
    this.player = replacement;
  }
  public void observe(PacketDirection direction, byte[] packet) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      int id = MinecraftInput.varInt(input);
      byte[] body = input.readAllBytes();
      if (direction == PacketDirection.CLIENT_TO_SERVER && session.state() == ConnectionState.LOGIN && protocol.is(ConnectionState.LOGIN, direction, id, PacketKind.LOGIN_START)) player = account = LoginStart.decode(body, protocol).unverifiedProfile();
      else if (direction == PacketDirection.SERVER_TO_CLIENT && session.state() == ConnectionState.LOGIN && protocol.is(ConnectionState.LOGIN, direction, id, PacketKind.LOGIN_SUCCESS)) {
        if (protocol.hasConfiguration()) session.beginConfiguration();
        else session.enterPlayFromLogin();
      }
    }
  }
}
