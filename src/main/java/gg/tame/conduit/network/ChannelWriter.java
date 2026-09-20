// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.network;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.TimeUnit;

/**
 * Writes to a non-blocking channel without ever parking the thread that writes.
 *
 * <p>A relay writes one socket from the thread reading the other, and on a non-blocking channel a
 * write that the peer's receive window cannot take returns short instead of waiting. What is left
 * is kept here and the selector is asked for writability; when the peer reads again, the selector
 * finishes it. So a peer that stops reading no longer holds a thread, which is the whole point of
 * moving off a thread per socket: a blocked write was one of the two ways a connection used to
 * keep one.
 *
 * <p>It cannot grow without limit. {@value #MAX_PENDING_BYTES} of unsent bytes, or
 * {@link #deadlineMillis} with no progress at all, ends the connection -- the same judgement
 * {@link DeadlineOutputStream} makes for a blocking socket, for the same reason: a peer that has
 * stopped reading and will not start again is not a peer worth keeping a buffer for.
 */
final class ChannelWriter extends OutputStream {
  /**
   * A player who has stopped reading has this much room before the connection is judged gone. Well
   * past a chunk-heavy join, and small enough that a thousand stalled connections cannot take the
   * heap with them.
   */
  static final int MAX_PENDING_BYTES = 2 * 1024 * 1024;

  private final SocketChannel channel;
  private final long deadlineNanos;
  private final Runnable wantsWritability;
  /** Unsent bytes are {@code pending[sent..filled)}. */
  private byte[] pending = new byte[8192];
  private int sent;
  private int filled;
  private long stalledSince;
  private volatile boolean broken;
  private String failure;

  ChannelWriter(SocketChannel channel, Runnable wantsWritability) {
    this.channel = channel;
    this.wantsWritability = wantsWritability;
    this.deadlineNanos = TimeUnit.MILLISECONDS.toNanos(
        Long.getLong("conduit.writeDeadlineMillis", DeadlineOutputStream.DEFAULT_MILLIS));
  }

  @Override public void write(int value) throws IOException { write(new byte[] {(byte) value}, 0, 1); }

  @Override public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
    if (broken) throw new IOException(failure);
    ensure(length);
    System.arraycopy(bytes, offset, pending, filled, length);
    filled += length;
  }

  /**
   * Sends what it can now and leaves the rest to the selector. It does not wait for the peer, so a
   * flush returning is not the bytes having arrived -- which is already true of a buffered stream
   * over a socket, and is what lets the caller go back to relaying.
   */
  @Override public synchronized void flush() throws IOException {
    if (broken) throw new IOException(failure);
    drainLocked();
    if (sent < filled) wantsWritability.run();
  }

  /** The selector, on a channel that has become writable again. */
  synchronized void onWritable() {
    try {
      drainLocked();
    } catch (IOException gone) {
      fail(gone.getMessage());
    }
  }

  /** Whether everything handed over has reached the socket. */
  synchronized boolean drained() { return sent == filled; }

  /** Why the connection was given up on, or null while it is fine. */
  String failure() { return broken ? failure : null; }

  /**
   * Blocks until everything queued has been written or the deadline passes, for the one moment that
   * has to wait: the disconnect a kicked player is owed, just before the socket is shut down.
   */
  void flushBeforeClose(long millis) {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
    while (System.nanoTime() < deadline) {
      synchronized (this) {
        if (broken) return;
        try { drainLocked(); } catch (IOException gone) { return; }
        if (sent == filled) return;
      }
      try { Thread.sleep(5); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
    }
  }

  private void drainLocked() throws IOException {
    while (sent < filled) {
      int wrote = channel.write(ByteBuffer.wrap(pending, sent, filled - sent));
      if (wrote <= 0) break;
      sent += wrote;
      stalledSince = 0;
    }
    if (sent == filled) {
      sent = filled = 0;
      stalledSince = 0;
      return;
    }
    // Still owed bytes: start the clock, or check it if it was already running.
    long now = System.nanoTime();
    if (stalledSince == 0) {
      stalledSince = now;
    } else if (now - stalledSince > deadlineNanos) {
      throw new IOException("no write progress for " + TimeUnit.NANOSECONDS.toMillis(deadlineNanos)
          + " ms; the peer stopped reading");
    }
  }

  private void ensure(int extra) throws IOException {
    if (filled + extra <= pending.length && sent == 0) return;
    if (sent > 0) {
      System.arraycopy(pending, sent, pending, 0, filled - sent);
      filled -= sent;
      sent = 0;
    }
    if (filled + extra <= pending.length) return;
    if (filled + extra > MAX_PENDING_BYTES) {
      fail("queued " + (filled + extra) + " unsent bytes; the peer stopped reading");
      throw new IOException(failure);
    }
    int capacity = pending.length;
    while (capacity < filled + extra) capacity <<= 1;
    pending = java.util.Arrays.copyOf(pending, Math.min(capacity, MAX_PENDING_BYTES));
  }

  private void fail(String reason) {
    if (broken) return;
    failure = reason;
    broken = true;
    try { channel.close(); } catch (IOException ignored) { }
  }

  @Override public void close() { try { channel.close(); } catch (IOException ignored) { } }
}
