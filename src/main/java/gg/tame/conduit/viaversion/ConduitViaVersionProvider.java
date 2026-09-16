package gg.tame.conduit.viaversion;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.protocol.version.BaseVersionProvider;

/**
 * Supplies the Conduit backend protocol as Via's "server" protocol for each connection.
 */
public final class ConduitViaVersionProvider extends BaseVersionProvider {
  @Override
  public ProtocolVersion getClosestServerProtocol(UserConnection connection) throws Exception {
    ConduitViaBackendTarget target = connection.get(ConduitViaBackendTarget.class);
    if (target != null && target.backendProtocol() != null) {
      return target.backendProtocol();
    }
    return super.getClosestServerProtocol(connection);
  }
}
