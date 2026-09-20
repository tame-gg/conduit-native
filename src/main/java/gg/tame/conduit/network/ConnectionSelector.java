// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.network;

import gg.tame.conduit.log.ConduitLog;
import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Watches every playing connection's sockets on a few threads instead of two per player.
 *
 * <p>A session used to be two threads parked in blocking reads, one per socket, for as long as the
 * player was online. On Windows those had to be platform threads (JDK-8334574; see
 * {@link SocketThreads}), so a thousand players were two thousand operating-system threads and two
 * thousand stacks. Nothing was running in them: a connection is idle between packets, and a parked
 * thread is what idleness cost.
 *
 * <p>Here a small fixed set of selector threads waits for readability, and the work of reading is
 * handed to a bounded worker pool. Between packets a connection costs a file descriptor and its
 * buffers and no thread at all, so the population a proxy can hold stops being a question about
 * threads. It also steps around JDK-8334574 rather than working around it: a selector is a platform
 * thread in {@code select()}, so no socket here is ever parked on by a virtual thread.
 *
 * <p>A connection is handed to exactly one worker at a time. Its read interest is taken away before
 * the worker starts and put back when the worker is done, so the selector cannot hand the same
 * connection to a second worker while the first is still in it, and nothing in {@link ChannelReader}
 * or the session behind it needs a lock for that. The selector is level-triggered, so bytes that
 * arrive in the gap between a worker finishing and its interest going back are not lost: the
 * interest returns and the selector reports the connection ready again at once.
 */
public final class ConnectionSelector implements AutoCloseable {
  /**
   * Selector threads. Two is enough to keep a ready queue moving on any machine this runs on --
   * each one only moves bytes between a socket and a buffer -- and a spare means one blocking
   * briefly on a registration does not stop the other.
   */
  private static final int SELECTOR_THREADS =
      Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 4));
  /**
   * Workers. These run the session: decoding, translating, plugin events and the write to the
   * opposite socket, so the pool is sized for work rather than for sockets. It is bounded because
   * an unbounded one is a thread per connection again by another name.
   */
  private static final int WORKER_THREADS =
      Math.max(4, Runtime.getRuntime().availableProcessors() * 2);

  private final Loop[] loops = new Loop[SELECTOR_THREADS];
  private final ExecutorService workers;
  private final AtomicInteger next = new AtomicInteger();
  private volatile boolean running = true;

  public ConnectionSelector() throws IOException {
    workers = Executors.newFixedThreadPool(WORKER_THREADS,
        Thread.ofPlatform().name("conduit-io-", 0).daemon(true).factory());
    for (int index = 0; index < loops.length; index++) {
      loops[index] = new Loop(index);
      Thread.ofPlatform().name("conduit-select-" + index).daemon(true).start(loops[index]);
    }
  }

  /** What a registered connection has to be able to do when its socket has something to say. */
  public interface Handler {
    /**
     * Reads and relays everything buffered, and returns whether the connection is still wanted.
     * Returning false, or throwing, unregisters it; the handler is what closes it.
     */
    boolean onReadable() throws IOException;
    /** The peer hung up, the channel failed, or the proxy is stopping. Called once. */
    void onClosed(String reason);
  }

  /** A registered connection: the one handle a session keeps to take itself off the selector again. */
  public final class Registration {
    final SocketChannel channel;
    final ChannelReader reader;
    final ChannelWriter writer;
    final Handler handler;
    final Loop loop;
    final AtomicBoolean busy = new AtomicBoolean();
    final AtomicBoolean done = new AtomicBoolean();
    volatile SelectionKey key;

    Registration(SocketChannel channel, ChannelReader reader, Handler handler, Loop loop) {
      this.channel = channel;
      this.reader = reader;
      this.handler = handler;
      this.loop = loop;
      this.writer = new ChannelWriter(channel, () -> loop.want(this, SelectionKey.OP_WRITE));
    }

    public java.io.InputStream input() { return reader; }
    public java.io.OutputStream output() { return writer; }
    /** Whether a whole frame is buffered; a worker reads only when it is. */
    public boolean hasCompleteFrame(int maximumFrameBytes) { return reader.hasCompleteFrame(maximumFrameBytes); }
    /** Takes whatever the channel has now: bytes added, 0 for none, -1 once the peer has hung up. */
    public int fill() throws IOException { return reader.fill(); }
    /** Sends what is queued before the socket is shut down, for a disconnect the peer is owed. */
    public void flushBeforeClose(long millis) { writer.flushBeforeClose(millis); }
    /** Takes the connection off the selector; the caller owns the channel from then on. */
    public void cancel() { finish(this, null); }
  }

  /**
   * Watches a connection. The reader must already hold anything read off the socket before now, and
   * the channel is put into non-blocking mode here.
   */
  public Registration register(SocketChannel channel, ChannelReader reader, Handler handler) throws IOException {
    if (!running) throw new IOException("the proxy is stopping");
    Loop loop = loops[Math.floorMod(next.getAndIncrement(), loops.length)];
    channel.configureBlocking(false);
    Registration registration = new Registration(channel, reader, handler, loop);
    loop.add(registration);
    return registration;
  }

  /** A reader for a channel that is about to be registered. */
  public static ChannelReader readerFor(SocketChannel channel) { return new ChannelReader(channel); }

  private void finish(Registration registration, String reason) {
    if (!registration.done.compareAndSet(false, true)) return;
    registration.loop.remove(registration);
    if (reason != null) registration.handler.onClosed(reason);
  }

  @Override public void close() {
    running = false;
    for (Loop loop : loops) loop.stop();
    workers.shutdownNow();
  }

  /** One selector thread: readiness in, worker tasks out. */
  private final class Loop implements Runnable {
    private final int index;
    private final Selector selector;
    private final Queue<Runnable> pending = new ArrayDeque<>();
    private volatile boolean alive = true;

    Loop(int index) throws IOException {
      this.index = index;
      this.selector = Selector.open();
    }

    void add(Registration registration) {
      submit(() -> {
        try {
          registration.key = registration.channel.register(selector, SelectionKey.OP_READ, registration);
        } catch (ClosedChannelException gone) {
          finish(registration, "the connection closed before it was watched");
        }
      });
    }

    void remove(Registration registration) {
      submit(() -> {
        SelectionKey key = registration.key;
        if (key != null) key.cancel();
      });
    }

    /** Adds an interest the worker threads cannot set themselves without racing the selector. */
    void want(Registration registration, int interest) {
      submit(() -> {
        SelectionKey key = registration.key;
        if (key == null || !key.isValid()) return;
        try { key.interestOps(key.interestOps() | interest); } catch (CancelledKeyException gone) { }
      });
    }

    private void submit(Runnable change) {
      synchronized (pending) { pending.add(change); }
      selector.wakeup();
    }

    void stop() {
      alive = false;
      selector.wakeup();
    }

    @Override public void run() {
      while (alive) {
        try {
          selector.select(1_000);
          applyPending();
          if (!alive) break;
          var ready = selector.selectedKeys().iterator();
          while (ready.hasNext()) {
            SelectionKey key = ready.next();
            ready.remove();
            dispatch(key);
          }
        } catch (java.nio.channels.ClosedSelectorException | IOException failure) {
          if (alive) ConduitLog.warn("connection selector " + index + ": " + failure);
          if (!selector.isOpen()) break;
        } catch (RuntimeException unexpected) {
          // One connection's accounting must not end the loop that serves all of them.
          ConduitLog.error("connection selector " + index, unexpected);
        }
      }
      try { selector.close(); } catch (IOException ignored) { }
    }

    private void applyPending() {
      while (true) {
        Runnable change;
        synchronized (pending) { change = pending.poll(); }
        if (change == null) return;
        try { change.run(); } catch (RuntimeException failed) { ConduitLog.warn("selector change: " + failed); }
      }
    }

    private void dispatch(SelectionKey key) {
      Registration registration = (Registration) key.attachment();
      if (registration == null || registration.done.get()) { key.cancel(); return; }
      try {
        if (key.isWritable()) {
          registration.writer.onWritable();
          if (registration.writer.drained()) key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
          String broken = registration.writer.failure();
          if (broken != null) { finish(registration, broken); return; }
        }
        if (!key.isReadable()) return;
        // Taken away for as long as a worker holds this connection, so the selector does not hand
        // it out twice and the reader stays a single thread's.
        key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);
        if (!registration.busy.compareAndSet(false, true)) return;
        try {
          workers.execute(() -> work(registration));
        } catch (RejectedExecutionException stopping) {
          registration.busy.set(false);
          finish(registration, "the proxy is stopping");
        }
      } catch (CancelledKeyException gone) {
        finish(registration, "the connection closed");
      }
    }

    private void work(Registration registration) {
      String reason = null;
      try {
        if (registration.done.get()) return;
        if (!registration.handler.onReadable()) reason = "the session ended";
      } catch (IOException gone) {
        reason = gone.getMessage() == null ? gone.getClass().getSimpleName() : gone.getMessage();
      } catch (RuntimeException unexpected) {
        ConduitLog.error("connection worker", unexpected);
        reason = "an error while relaying";
      } finally {
        registration.busy.set(false);
      }
      if (reason != null) {
        finish(registration, reason);
        return;
      }
      // Back on watch. Level-triggered, so anything that arrived while the worker ran is reported
      // straight away rather than waiting for the next byte after it.
      want(registration, SelectionKey.OP_READ);
    }
  }
}
