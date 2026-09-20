// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.network;

import gg.tame.conduit.crypto.AesCfb8;
import gg.tame.conduit.crypto.CipherStreams;
import gg.tame.conduit.metrics.ConduitMetrics;
import gg.tame.conduit.protocol.MinecraftFrames;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import javax.crypto.Cipher;

/** Raw socket framing with an optional AES/CFB8 layer applied to the entire byte stream. */
public final class PacketTransport {
  private final Socket socket;
  private InputStream input;
  private OutputStream output;
  private EncryptionState state = EncryptionState.PLAINTEXT;
  private final Object writeLock = new Object();
  /** What {@link #setReadTimeoutMillis} last asked for: the bound on any one read. */
  private volatile int readTimeoutMillis;
  /** {@link System#nanoTime} by which every read must have finished, or 0 for none. */
  private volatile long readDeadline;
  public PacketTransport(Socket socket) throws IOException {
    this.socket = socket;
    socket.setTcpNoDelay(true);
    this.readTimeoutMillis = socket.getSoTimeout();
    this.input = new DeadlineInput(socket.getInputStream());
    this.output = new BufferedOutputStream(DeadlineOutputStream.of(socket), 8192);
  }
  public PacketTransport(InputStream input, OutputStream output) { this.socket = null; this.input = input; this.output = output; }
  /** Bounds a read that would otherwise park forever; 0 waits indefinitely, and ends any {@link #setReadDeadline}. */
  public void setReadTimeoutMillis(int millis) throws IOException {
    readTimeoutMillis = millis;
    if (millis == 0) readDeadline = 0;
    // A socket on the selector is non-blocking and has no read to bound: a worker reads it only
    // when a whole frame is already there, and never waits on it.
    if (socket != null && attached == null) socket.setSoTimeout(millis);
  }
  /**
   * Every read from now until {@code setReadTimeoutMillis(0)} has to be over within {@code millis},
   * however the bytes arrive. The read timeout bounds only the wait for the next byte, so a peer that
   * declared a large frame and then sent one byte of it every so often was never timed out: a login
   * that never finished held its thread, its connection slot and its throttle lease for hours.
   */
  public void setReadDeadline(long millis) {
    readDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(1, millis));
  }
  public EncryptionState state() { return state; }
  public byte[] read(int maximumFrameBytes) throws IOException {
    byte[] packet = MinecraftFrames.read(input, maximumFrameBytes);
    ConduitMetrics.current().inbound(packet.length);
    return packet;
  }
  public void write(byte[] packet) throws IOException {
    writeUnflushed(packet);
    flush();
  }
  public void writeUnflushed(byte[] packet) throws IOException {
    synchronized (writeLock) {
      MinecraftFrames.writeUnflushed(output, packet);
      ConduitMetrics.current().outbound(packet.length);
    }
  }
  public void flush() throws IOException {
    synchronized (writeLock) { output.flush(); }
  }
  public int available() throws IOException { return input.available(); }
  /**
   * Whether the peer has hung up, asked while it owes nothing -- a client waiting for its login to
   * be answered. A socket shows a hang-up only to a read, so this reads, for a millisecond; a byte
   * that did arrive stays the next one read.
   */
  public boolean hungUp() {
    if (socket == null) return false;
    ConnectionSelector.Registration registration = attached;
    if (registration != null) {
      // No blocking read to ask with, and none needed: the buffer is fed from the channel, so an
      // end of stream has already been seen by the fill that found it.
      try { return registration.fill() < 0; } catch (IOException gone) { return true; }
    }
    try {
      // Through the field the login deadline reads too: set on the socket alone, the 1 ms was
      // replaced by the whole read timeout before the read, and every login waited that long.
      int timeout = readTimeoutMillis;
      int next;
      readTimeoutMillis = 1;
      socket.setSoTimeout(1);
      try { next = input.read(); }
      finally { readTimeoutMillis = timeout; socket.setSoTimeout(timeout); }
      if (next < 0) return true;
      PushbackInputStream kept = new PushbackInputStream(input, 1);
      kept.unread(next);
      input = kept;
      return false;
    } catch (SocketTimeoutException quiet) {
      return false;
    } catch (IOException gone) {
      return true;
    }
  }
  public void beginNegotiation() {
    if (state != EncryptionState.PLAINTEXT) throw new IllegalStateException("encryption negotiation is not valid in " + state);
    state = EncryptionState.ENCRYPTION_NEGOTIATING;
  }
  public void enableEncryption(byte[] sharedSecret) {
    if (state != EncryptionState.ENCRYPTION_NEGOTIATING && state != EncryptionState.PLAINTEXT) {
      throw new IllegalStateException("cannot enable encryption in " + state);
    }
    Cipher encrypt = AesCfb8.encryptor(sharedSecret);
    Cipher decrypt = AesCfb8.decryptor(sharedSecret);
    input = CipherStreams.decrypting(input, decrypt);
    output = CipherStreams.encrypting(output, encrypt);
    // Kept because the buffer that replaces this stream on the selector has to decrypt for itself:
    // the frame length is inside the encrypted stream, so ciphertext cannot be asked whether it
    // holds a whole frame. The same cipher, carrying on from exactly where this stream left it.
    this.decryptCipher = decrypt;
    this.encryptCipher = encrypt;
    state = EncryptionState.ENCRYPTED;
  }
  private Cipher decryptCipher;
  private Cipher encryptCipher;
  private volatile ConnectionSelector.Registration attached;

  /**
   * Moves this connection onto the selector: from a thread parked in a blocking read to a socket
   * watched with everyone else's, read by a worker only when it has something to say.
   *
   * <p>Null when there is no channel behind the socket -- a transport built over plain streams, as
   * the tests build one -- and the caller then keeps whatever it was doing. Everything the stream
   * had already taken off the socket moves into the buffer, so no byte is lost at the changeover,
   * and the deadline goes away with the blocking read it used to bound.
   */
  public ConnectionSelector.Registration attachTo(ConnectionSelector selector, ConnectionSelector.Handler handler) throws IOException {
    java.nio.channels.SocketChannel channel = socket == null ? null : socket.getChannel();
    if (channel == null) return null;
    if (state == EncryptionState.CLOSED) throw new IOException("the connection closed during login");
    readDeadline = 0;
    // Everything the old streams hold is dealt with while the channel is still blocking. Registering
    // first makes it non-blocking, and this flush -- through the socket's own output stream -- then
    // throws IllegalBlockingModeException, which only shows up when the buffer is not already empty.
    synchronized (writeLock) { output.flush(); }
    // Plaintext the old chain is holding: at most what the socket had when it was last asked, plus
    // the one byte hungUp() may have pushed back.
    int buffered = input.available();
    byte[] carried = buffered > 0 ? input.readNBytes(buffered) : new byte[0];
    ConnectionSelector.Registration registration = selector.register(channel, carried, decryptCipher, handler);
    synchronized (writeLock) {
      this.input = registration.input();
      // The encrypting layer is put back on top of the new stream. Replacing the whole chain sent
      // an encrypted client everything after its login in plaintext, and it sat there waiting for a
      // packet it could read. The reader needs no such layer: it decrypts as it fills, because the
      // frame length is inside the encrypted stream.
      this.output = encryptCipher == null ? registration.output()
          : CipherStreams.encrypting(registration.output(), encryptCipher);
    }
    this.attached = registration;
    return registration;
  }

  /** Whether there is a channel behind the socket, which a connection has to have to be watched. */
  public boolean selectable() { return socket != null && socket.getChannel() != null; }

  /** Fills until a whole frame is buffered; false when the socket has no more to give just now. */
  public boolean nextFrameReady(int maximumFrameBytes) throws IOException {
    ConnectionSelector.Registration registration = attached;
    return registration == null || registration.nextFrameReady(maximumFrameBytes);
  }

  /** Whether what this connection's packets are written to is too far behind to be given more. */
  public boolean sinkCongested() {
    ConnectionSelector.Registration registration = attached;
    return registration != null && registration.sinkCongested();
  }

  /** Whether the peer has hung up and everything it sent has been read. */
  public boolean ended() {
    ConnectionSelector.Registration registration = attached;
    return registration != null && registration.ended();
  }

  /** Takes whatever the socket has now, for a worker draining a connection; 0 off the selector. */
  public int fill() throws IOException {
    ConnectionSelector.Registration registration = attached;
    return registration == null ? 0 : registration.fill();
  }
  public InputStream input() { return input; }
  /** The port the peer connected from, or 0 when there is no socket. */
  public int remotePort() {
    if (declaredRemote != null) return declaredRemote.getPort();
    return socket == null ? 0 : socket.getPort();
  }
  /**
   * The client's real address when a PROXY protocol header named one, so that the port reported
   * for the player is the port the player dialled from and not the reverse proxy's own.
   */
  private volatile java.net.InetSocketAddress declaredRemote;
  public void declareRemote(java.net.InetSocketAddress remote) { this.declaredRemote = remote; }
  /** The socket's input, each read bounded by what is left of the deadline while there is one. */
  private final class DeadlineInput extends java.io.FilterInputStream {
    DeadlineInput(InputStream in) { super(in); }
    @Override public int read() throws IOException { bound(); return super.read(); }
    @Override public int read(byte[] buffer, int offset, int length) throws IOException { bound(); return super.read(buffer, offset, length); }
    private void bound() throws IOException {
      long deadline = readDeadline;
      if (deadline == 0) return;
      long left = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
      if (left <= 0) throw new java.net.SocketTimeoutException("login did not finish in time");
      int timeout = readTimeoutMillis;
      socket.setSoTimeout((int) (timeout == 0 ? Math.min(left, Integer.MAX_VALUE) : Math.min(timeout, left)));
    }
  }
  public OutputStream output() { return output; }
  /**
   * Ends the connection, not merely the session's view of it.
   *
   * <p>A session that closes itself — a backend that dropped with nowhere to fall back to, a
   * translator that failed, a plugin disconnecting a player — is the only thing that ever calls
   * this. Marking the state and leaving the socket open left the client reader parked in a read
   * nothing would complete, so the worker never returned, and its connection slot and its
   * per-source throttle lease were both held until the client itself hung up.
   *
   * <p>The output is ended first and the socket closed a moment later. Closed at once, a socket with
   * bytes of the client's still unread -- a playing client is always sending -- was reset rather
   * than ended, and a client that took the reset before reading the disconnect it had just been sent
   * lost it: a kicked player saw "Connection reset" instead of the reason. Ending the output sends
   * the disconnect ahead of the end of the stream, and the close follows within {@value #LINGER_MILLIS}
   * ms whatever the client does, so a reader parked on this socket is still let go.
   */
  public void close() {
    state = EncryptionState.CLOSED;
    // Already closed, or already ending with its close to follow: a second close must not cut that short.
    if (socket == null || socket.isClosed() || socket.isOutputShutdown()) return;
    ConnectionSelector.Registration registration = attached;
    if (registration != null) {
      // The disconnect the peer is owed is still queued: on the selector a write returns before the
      // bytes leave, so shutting the output down now would take the reason with it.
      registration.flushBeforeClose(LINGER_MILLIS);
      registration.cancel();
    }
    try {
      socket.shutdownOutput();
      LINGER.schedule(() -> { try { socket.close(); } catch (IOException ignored) { } }, LINGER_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS);
      return;
    } catch (IOException | java.util.concurrent.RejectedExecutionException alreadyGone) {
      // Closed already, or never connected: nothing is owed to the peer.
    }
    try { socket.close(); } catch (IOException ignored) { }
  }
  static final long LINGER_MILLIS = 250;
  /** Closes the sockets whose output has ended; it never blocks on a socket itself, only closes them. */
  private static final java.util.concurrent.ScheduledExecutorService LINGER = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
      runnable -> Thread.ofPlatform().name("conduit-linger").daemon(true).unstarted(runnable));
}
