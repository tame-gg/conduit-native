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
    channel.pipeline().addLast("via-decoder", new ChannelInboundHandlerAdapter() {
      @Override
      public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ByteBuf buf) {
          try {
            byte[] copy = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), copy);
            Object session = ctx.channel().attr(ConduitViaSessionHolder.KEY).get();
            if (session instanceof ConduitViaSession via) {
              via.extrasToBackend.offer(copy);
            }
          } finally {
            buf.release();
          }
          return;
        }
        ctx.fireChannelRead(msg);
      }
    });
    channel.pipeline().addLast("via-encoder", new ChannelOutboundHandlerAdapter() {
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

  public void syncState(ConnectionState state) {
    State via = ConduitViaStates.toVia(state);
    connection.getProtocolInfo().setClientState(via);
    connection.getProtocolInfo().setServerState(via);
  }

  public byte[] transformClientToBackend(ConnectionState state, byte[] packet) {
    syncState(state);
    return transform(packet, true);
  }

  public byte[] transformBackendToClient(ConnectionState state, byte[] packet) {
    syncState(state);
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
