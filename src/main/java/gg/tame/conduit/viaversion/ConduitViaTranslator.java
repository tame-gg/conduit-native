package gg.tame.conduit.viaversion;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.ProtocolTranslator;
import gg.tame.conduit.protocol.translate.TranslationException;
import java.util.List;

/** {@link ProtocolTranslator} backed by the ViaVersion ecosystem. */
public final class ConduitViaTranslator implements ProtocolTranslator, AutoCloseable {
  private volatile ConduitViaSession session;

  private ConduitViaTranslator(ConduitViaSession session) {
    this.session = session;
  }

  public static ConduitViaTranslator create(int clientProtocol, int backendProtocol, String host, int port) {
    return new ConduitViaTranslator(ConduitViaSession.open(clientProtocol, backendProtocol, host, port));
  }

  public String engineName() {
    return "ViaVersion";
  }

  /** Announces a backend state transition Conduit performed itself. */
  public void backendEntered(ConnectionState state) {
    session.setServerState(state);
  }

  /** Announces a client state transition Conduit performed on the client's behalf. */
  public void adoptClientState(ConnectionState state) {
    session.adoptClientState(state);
  }

  /** Handler names on this session's channel, in pipeline order. */
  public java.util.List<String> pipelineHandlerNames() {
    return session.pipelineHandlerNames();
  }

  /** Via's own client/server state pair, for diagnostics and traces. */
  public String stateDescription() {
    return session.stateDescription();
  }

  public void rebindBackend(int backendProtocol, String host, int port) {
    this.session = session.rebindBackend(backendProtocol, host, port);
  }

  @Override
  public byte[] clientToBackend(ConnectionState state, byte[] packet) {
    try {
      return session.transformClientToBackend(state, packet);
    } catch (TranslationException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new TranslationException("Via client→backend failed: " + exception.getMessage(), exception);
    }
  }

  @Override
  public byte[] backendToClient(ConnectionState state, byte[] packet) {
    try {
      return session.transformBackendToClient(state, packet);
    } catch (TranslationException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new TranslationException("Via backend→client failed: " + exception.getMessage(), exception);
    }
  }

  @Override
  public List<byte[]> drainToClient() {
    return session.drainToClient();
  }

  @Override
  public List<byte[]> drainToBackend() {
    return session.drainToBackend();
  }

  @Override
  public void close() {
    session.close();
  }
}
