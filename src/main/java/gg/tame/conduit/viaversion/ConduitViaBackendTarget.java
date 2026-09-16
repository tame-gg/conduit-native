package gg.tame.conduit.viaversion;

import com.viaversion.viaversion.api.connection.StorableObject;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;

/**
 * Per-connection backend target stored on a Via {@code UserConnection}.
 * Independently authored for Conduit; not derived from another proxy.
 */
public final class ConduitViaBackendTarget implements StorableObject {
  private volatile ProtocolVersion backendProtocol;

  public ConduitViaBackendTarget(ProtocolVersion backendProtocol) {
    this.backendProtocol = backendProtocol;
  }

  public ProtocolVersion backendProtocol() {
    return backendProtocol;
  }

  public void setBackendProtocol(ProtocolVersion backendProtocol) {
    this.backendProtocol = backendProtocol;
  }
}
