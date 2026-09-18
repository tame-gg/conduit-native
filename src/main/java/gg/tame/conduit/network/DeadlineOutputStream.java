package gg.tame.conduit.network;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * A socket's output stream that closes the socket when a write stops making progress.
 *
 * <p>{@code setSoTimeout} only bounds reads. A peer that keeps its connection open and stops
 * reading fills its receive window, and after that a blocking write parks until the peer reads
 * again, which may be never. Both of a session's threads can end up waiting on that one peer. The
 * thread relaying the other side sits in the write. The thread reading the peer sits in a read the
 * peer never answers. Nothing else ends the session, so its threads, its connection slot and its
 * throttle lease were held until the peer went away on its own.
 *
 * <p>Writes go to the socket in chunks of at most {@value #CHUNK} bytes. A single daemon watchdog
 * closes the socket once one chunk has been in flight for longer than the deadline. Closing the
 * socket throws the blocked write out, whether it runs on a platform or a virtual thread, and it
 * also throws out the session's read of the same socket. The session then ends by the path it
 * already takes for a lost connection. A peer that reads slowly keeps completing chunks and is
 * never cut off. Only a write that makes no progress at all for the whole deadline ends the
 * connection.
 *
 * <p>The deadline is {@value #DEFAULT_MILLIS} ms. The {@code conduit.writeDeadlineMillis} system
 * property overrides it when the stream is created; that exists so tests can use a short one.
 */
public final class DeadlineOutputStream extends OutputStream {
  static final long DEFAULT_MILLIS = 30_000;
  private static final int CHUNK = 8192;
  private static final long TICK_MILLIS = 500;
  private static final Set<DeadlineOutputStream> WRITING = ConcurrentHashMap.newKeySet();

  static {
    // A platform daemon: it never blocks on a socket itself, it only closes them.
    Thread.ofPlatform().name("conduit-write-deadline").daemon(true).start(DeadlineOutputStream::watch);
  }

  private final Socket socket;
  private final OutputStream out;
  private final long deadlineNanos;
  private volatile long chunkStarted;

  private DeadlineOutputStream(Socket socket, long deadlineMillis) throws IOException {
    this.socket = socket;
    this.out = socket.getOutputStream();
    this.deadlineNanos = TimeUnit.MILLISECONDS.toNanos(deadlineMillis);
  }

  /** The socket's output stream, closed with its socket when a write makes no progress for the deadline. */
  public static OutputStream of(Socket socket) throws IOException {
    return new DeadlineOutputStream(socket, Long.getLong("conduit.writeDeadlineMillis", DEFAULT_MILLIS));
  }

  @Override public void write(int value) throws IOException { write(new byte[] {(byte) value}, 0, 1); }

  @Override public void write(byte[] bytes, int offset, int length) throws IOException {
    while (length > 0) {
      int chunk = Math.min(length, CHUNK);
      chunkStarted = System.nanoTime();
      WRITING.add(this);
      try {
        out.write(bytes, offset, chunk);
      } finally {
        WRITING.remove(this);
      }
      offset += chunk;
      length -= chunk;
    }
  }

  @Override public void flush() throws IOException { out.flush(); }

  @Override public void close() throws IOException { out.close(); }

  private static void watch() {
    while (true) {
      try {
        Thread.sleep(TICK_MILLIS);
      } catch (InterruptedException ignored) {
        // Nothing interrupts this thread on purpose; it keeps watching.
      }
      long now = System.nanoTime();
      for (DeadlineOutputStream stream : WRITING) {
        // Removing it here, not merely finding it, is what proves the chunk is still in flight.
        if (now - stream.chunkStarted <= stream.deadlineNanos || !WRITING.remove(stream)) continue;
        gg.tame.conduit.log.ConduitLog.warn("Closing " + stream.socket.getRemoteSocketAddress() + ": no write progress for "
            + TimeUnit.NANOSECONDS.toMillis(stream.deadlineNanos) + " ms; the peer stopped reading");
        try { stream.socket.close(); } catch (IOException ignored) { }
      }
    }
  }
}
