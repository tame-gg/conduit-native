package gg.tame.conduit.viaversion;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.exception.CancelDecoderException;
import com.viaversion.viaversion.exception.CancelEncoderException;
import com.viaversion.viaversion.protocol.ProtocolPipelineImpl;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.translate.TranslationException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.CodecException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * One Via {@link UserConnection} bound to a Conduit player translation context.
 *
 * <p>Conduit I/O remains stream-based. This session uses an {@link EmbeddedChannel} only so Via's
 * public {@code sendRawPacket*} paths have named encoder/decoder handlers to target when a
 * transform emits extra packets.
 */
public final class ConduitViaSession implements AutoCloseable {
  /**
   * Handler names Via looks the pipeline up by. They must agree with what
   * {@link ConduitViaInjector} reports, which is how Via finds the two ends of this session.
   */
  static final String HEAD_HANDLER = "conduit-head";
  static final String DECODER_HANDLER = ConduitViaInjector.DECODER_NAME;
  static final String ENCODER_HANDLER = ConduitViaInjector.ENCODER_NAME;

  private final EmbeddedChannel channel;
  private final UserConnection connection;
  private final ConcurrentLinkedQueue<byte[]> extrasToClient = new ConcurrentLinkedQueue<>();
  private final ConcurrentLinkedQueue<byte[]> extrasToBackend = new ConcurrentLinkedQueue<>();
  private final int clientProtocol;
  private volatile int backendProtocol;

  private ConduitViaSession(EmbeddedChannel channel, UserConnection connection, int clientProtocol, int backendProtocol) {
    this.channel = channel;
    this.connection = connection;
    this.clientProtocol = clientProtocol;
    this.backendProtocol = backendProtocol;
  }

  public static ConduitViaSession open(int clientProtocol, int backendProtocol, String host, int port)
      throws TranslationException {
    if (!ConduitViaBootstrap.available()) {
      throw new TranslationException("ViaVersion is not available");
    }
    ProtocolVersion client = ProtocolVersion.getProtocol(clientProtocol);
    ProtocolVersion backend = ProtocolVersion.getProtocol(backendProtocol);
    if (client == null || backend == null || !client.isKnown() || !backend.isKnown()) {
      throw new TranslationException("ViaVersion does not know protocol pair "
          + clientProtocol + " → " + backendProtocol);
    }

    EmbeddedChannel channel = new EmbeddedChannel();
    // Via addresses the serverbound side by asking its injector for the decoder's handler name and
    // then firing a read at whatever sits *before* it. A pipeline whose first entry is the decoder
    // has nothing before it, and the lookup fails, so this anchor exists purely to be that
    // predecessor. It carries no behaviour of its own.
    channel.pipeline().addLast(HEAD_HANDLER, new ChannelInboundHandlerAdapter());
    channel.pipeline().addLast(DECODER_HANDLER, new ChannelInboundHandlerAdapter() {
      @Override
      public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ByteBuf buf) {
          try {
            Object holder = ctx.channel().attr(ConduitViaSessionHolder.KEY).get();
            if (holder instanceof ConduitViaSession via) {
              // Extra serverbound packets arrive carrying Via's passthrough marker, which says
              // "already in the backend's dialect, do not translate again". Handing the buffer
              // back to Via is what consumes that marker and leaves the packet body behind;
              // stripping it here by hand would both duplicate Via's framing and leak the
              // one-shot token it just issued.
              via.connection.transformServerbound(buf, CancelDecoderException::generate);
              if (buf.isReadable()) {
                byte[] copy = new byte[buf.readableBytes()];
                buf.getBytes(buf.readerIndex(), copy);
                via.extrasToBackend.offer(copy);
              }
            }
          } catch (Exception cancelledOrFailed) {
            // A cancelled extra is simply not forwarded.
          } finally {
            buf.release();
          }
          return;
        }
        ctx.fireChannelRead(msg);
      }
    });
    channel.pipeline().addLast(ENCODER_HANDLER, new ChannelOutboundHandlerAdapter() {
      @Override
      public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (msg instanceof ByteBuf buf) {
          try {
            byte[] copy = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), copy);
            Object session = ctx.channel().attr(ConduitViaSessionHolder.KEY).get();
            if (session instanceof ConduitViaSession via) {
              via.extrasToClient.offer(copy);
            }
            promise.setSuccess();
          } finally {
            buf.release();
          }
          return;
        }
        ctx.write(msg, promise);
      }
    });

    // Frontend (player-facing) connection: clientSide=false.
    UserConnection connection = new UserConnectionImpl(channel, false);
    new ProtocolPipelineImpl(connection);
    connection.put(new ConduitViaBackendTarget(backend));

    ConduitViaSession session = new ConduitViaSession(channel, connection, clientProtocol, backendProtocol);
    channel.attr(ConduitViaSessionHolder.KEY).set(session);

    // Prime Via's base protocol using a synthetic login handshake. Conduit already consumed the
    // real client handshake and writes its own backend handshake separately.
    try {
      byte[] handshake = new Handshake(clientProtocol, host == null ? "conduit" : host, port <= 0 ? 25565 : port, 2).encode();
      ByteBuf buf = Unpooled.wrappedBuffer(handshake);
      try {
        connection.getProtocolInfo().setClientState(State.HANDSHAKE);
        connection.getProtocolInfo().setServerState(State.HANDSHAKE);
        connection.transformServerbound(buf, CancelDecoderException::generate);
      } finally {
        buf.release();
      }
      connection.getProtocolInfo().setClientState(State.LOGIN);
      connection.getProtocolInfo().setServerState(State.LOGIN);
      // Discard any extras produced while priming.
      session.extrasToClient.clear();
      session.extrasToBackend.clear();
      while (channel.readOutbound() != null) {
        // drain
      }
    } catch (CodecException cancelled) {
      throw new TranslationException("ViaVersion cancelled handshake priming for "
          + clientProtocol + " → " + backendProtocol, cancelled);
    } catch (Exception failure) {
      channel.close();
      throw new TranslationException("ViaVersion failed to prime "
          + clientProtocol + " → " + backendProtocol + ": " + failure.getMessage(), failure);
    }

    if (!connection.shouldTransformPacket() && clientProtocol != backendProtocol) {
      channel.close();
      throw new TranslationException("ViaVersion has no active translation pipe for "
          + clientProtocol + " → " + backendProtocol);
    }
    return session;
  }

  /**
   * Aligns Via's view of the client-facing state with Conduit's, but only until Via has one of
   * its own.
   *
   * <p>Neither half of the state belongs to Conduit once login is over. Via moves the client
   * between LOGIN, CONFIGURATION and PLAY as it writes the packets that cause those transitions —
   * it is the thing that produced the client's Login Success — and it moves the backend the same
   * way. Conduit's own view is a single connection state owned by whichever thread last touched
   * it, and forcing it onto Via breaks both halves in turn: it collapses the split the 1.20.2
   * boundary is built around (old client in Play, modern backend still in Configuration), and it
   * loses races. A 1.13 backend answers Login Success with Join Game immediately, on the backend
   * reader thread, while the client's own Login Acknowledged is still in flight to the client
   * reader; Conduit still reads LOGIN, Via is told LOGIN, and the one Join Game of the session is
   * decoded in the wrong state and dropped. The client then waits forever for a Finish
   * Configuration that the packet it was buffered behind would have produced.
   *
   * <p>So this only primes: it seeds the client state while Via is still in the handshake it was
   * opened with, and says nothing after that.
   */
  public void syncClientState(ConnectionState state) {
    if (connection.getProtocolInfo().getClientState() != State.HANDSHAKE) {
      return;
    }
    connection.getProtocolInfo().setClientState(ConduitViaStates.toVia(state));
  }

  /**
   * Tells Via that the backend half moved on without it.
   *
   * <p>Via normally learns the backend state from the packets that cause the transition, but
   * Conduit owns the backend login: it writes Login Acknowledged itself, from the backend's own
   * protocol definition, and that packet never reaches Via. Without this, Via keeps decoding the
   * backend as if it were still in Login and mis-reads the first Configuration packet it is given.
   */
  public void setServerState(ConnectionState state) {
    connection.getProtocolInfo().setServerState(ConduitViaStates.toVia(state));
  }

  /**
   * Places a freshly opened session at the states its connection has already reached.
   *
   * <p>A session opened for a first connection learns both states by watching the packets that
   * cause the transitions: Conduit forwards the backend's Login Success through it, and Via moves
   * the client on from Login itself. A session opened for a <em>server switch</em> sees none of
   * that. Conduit performs that login on the player's behalf and deliberately withholds the new
   * backend's Login Success from the client, so nothing that changes state ever reaches Via, and it
   * stays in the Login it was opened with — passing every packet through untranslated, including
   * the Join Game a downgrade path needs in order to build the client's Configuration phase.
   *
   * <p>So the states are handed over explicitly, and only here: at a point where Conduit performed
   * both transitions itself and therefore genuinely knows them. This is the same reason
   * {@link #setServerState} exists, applied to both halves at once.
   */
  public void adoptStates(ConnectionState clientState, ConnectionState backendState) {
    connection.getProtocolInfo().setClientState(ConduitViaStates.toVia(clientState));
    connection.getProtocolInfo().setServerState(ConduitViaStates.toVia(backendState));
  }

  /** Handler names on this session's channel, in pipeline order. */
  public List<String> pipelineHandlerNames() {
    return new ArrayList<>(channel.pipeline().names());
  }

  /** Via's own view of the connection, as {@code client/server}. Diagnostics only. */
  public String stateDescription() {
    return connection.getProtocolInfo().getClientState() + "/" + connection.getProtocolInfo().getServerState();
  }

  public byte[] transformClientToBackend(ConnectionState state, byte[] packet) {
    syncClientState(state);
    return transform(packet, true);
  }

  public byte[] transformBackendToClient(ConnectionState state, byte[] packet) {
    syncClientState(state);
    return transform(packet, false);
  }

  private byte[] transform(byte[] packet, boolean serverbound) {
    if (packet == null) return null;
    ByteBuf buf = Unpooled.buffer(packet.length);
    try {
      buf.writeBytes(packet);
      if (serverbound) {
        connection.transformServerbound(buf, CancelDecoderException::generate);
      } else {
        connection.transformClientbound(buf, CancelEncoderException::generate);
      }
      byte[] out = new byte[buf.readableBytes()];
      buf.readBytes(out);
      drainEmbeddedOutbound();
      return out;
    } catch (CodecException cancelled) {
      drainEmbeddedOutbound();
      return null;
    } catch (RuntimeException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new TranslationException("ViaVersion transform failed: " + failure.getMessage(), failure);
    } finally {
      buf.release();
    }
  }

  private void drainEmbeddedOutbound() {
    // Via does not always emit an extra packet inline: some paths hand it to the channel's event
    // loop instead. An EmbeddedChannel runs that queue only when asked, so without this the packet
    // sits there until some unrelated later operation happens to drain it, and Conduit forwards it
    // in a state where its id means something else entirely — a Login Acknowledged arriving after
    // the backend reached Play reads as a packet with a body and kills the connection.
    try {
      channel.runPendingTasks();
    } catch (RuntimeException ignored) {
      // A failed extra must not take the packet that produced it down with it.
    }
    Object outbound;
    while ((outbound = channel.readOutbound()) != null) {
      if (outbound instanceof ByteBuf buf) {
        try {
          byte[] copy = new byte[buf.readableBytes()];
          buf.getBytes(buf.readerIndex(), copy);
          extrasToClient.offer(copy);
        } finally {
          buf.release();
        }
      }
    }
  }

  public List<byte[]> drainToClient() {
    return drain(extrasToClient);
  }

  public List<byte[]> drainToBackend() {
    return drain(extrasToBackend);
  }

  private static List<byte[]> drain(ConcurrentLinkedQueue<byte[]> queue) {
    List<byte[]> packets = new ArrayList<>();
    byte[] next;
    while ((next = queue.poll()) != null) {
      packets.add(next);
    }
    return packets;
  }

  public int clientProtocol() {
    return clientProtocol;
  }

  public int backendProtocol() {
    return backendProtocol;
  }

  public UserConnection connection() {
    return connection;
  }

  /**
   * Rebuilds Via state for a new backend protocol after a server switch.
   * Client protocol remains unchanged.
   */
  public ConduitViaSession rebindBackend(int newBackendProtocol, String host, int port) throws TranslationException {
    close();
    return open(clientProtocol, newBackendProtocol, host, port);
  }

  @Override
  public void close() {
    try {
      connection.clearStoredObjects();
    } catch (RuntimeException ignored) {
    }
    try {
      channel.finishAndReleaseAll();
    } catch (RuntimeException ignored) {
    }
    try {
      if (channel.isOpen()) {
        channel.close();
      }
    } catch (RuntimeException ignored) {
    }
  }
}
