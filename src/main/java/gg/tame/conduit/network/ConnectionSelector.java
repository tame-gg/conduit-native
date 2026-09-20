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

  /**
   * How often a selector looks for a connection whose peer has stopped reading, and so also the
   * longest it waits in {@code select}.
   *
   * <p>Well under the write deadline on purpose. Sampled at the deadline itself, a peer that is
   * reading slowly but steadily is judged on whichever single instant the sweep happened to land
   * on, and a full send buffer makes that instant look like a dead connection; sampled ten times
   * within it, any byte that moves is seen and the connection is kept.
   */
  private static final long SWEEP_INTERVAL_MILLIS = 100;
  private static final long SWEEP_INTERVAL_NANOS =
      java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(SWEEP_INTERVAL_MILLIS);

  /** How much a paused connection may have buffered before the sweep stops topping it up. */
  private static final int PAUSED_READ_CEILING_BYTES = 64 * 1024;

  private static final ThreadLocal<Boolean> IN_WORKER = new ThreadLocal<>();

  /**
   * Whether this thread is relaying a connection for the selector.
   *
   * <p>Work that would block for long -- opening a backend and logging in to it for a server switch
   * -- must not run on one of these: the pool is bounded, and a handful of switches would take every
   * thread that the rest of the players are relayed on. Such work goes to a thread of its own.
   */
  public static boolean onWorkerThread() { return IN_WORKER.get() != null; }

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
    /** The connection this one's reads are written to, whose congestion pauses those reads. */
    private volatile Registration sink;

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
    /** Fills until a whole frame is buffered; false when the socket has no more to give just now. */
    public boolean nextFrameReady(int maximumFrameBytes) throws IOException { return reader.nextFrameReady(maximumFrameBytes); }
    /** Whether the peer has hung up and everything it sent has been read. */
    public boolean ended() { return reader.ended(); }
    /**
     * Whether what this feeds is too far behind to be given more. A relay asks between packets and
     * stops when it says yes: read on regardless and a flooding server is buffered here in full for
     * a client reading a fraction of it, which is what the bound in ChannelWriter would then end
     * the connection over.
     */
    public boolean sinkCongested() {
      Registration downstream = sink;
      return downstream != null && !downstream.done.get() && downstream.writer.congested();
    }
    /** Sends what is queued before the socket is shut down, for a disconnect the peer is owed. */
    public void flushBeforeClose(long millis) { writer.flushBeforeClose(millis); }
    /**
     * Says where what is read here ends up, which is what makes backpressure work: while that
     * connection's peer is behind, this one stops being read, and the operating system slows its
     * own peer down rather than the difference piling up in the proxy. A switch points this at the
     * backend the player moved to.
     */
    public void feeds(Registration sink) { this.sink = sink; }

    /** Takes the connection off the selector; the caller owns the channel from then on. */
    public void cancel() { finish(this, null); }
  }

  /** Watches a connection that was never encrypted. */
  public Registration register(SocketChannel channel, byte[] carriedPlaintext, Handler handler) throws IOException {
    return register(channel, carriedPlaintext, null, handler);
  }

  /**
   * Watches a connection, from here on non-blocking.
   *
   * <p>{@code carriedPlaintext} is what the blocking stream being replaced had already taken off the
   * socket and not yet handed out; without it those bytes would be lost at the changeover. {@code
   * decrypt} is the cipher that stream was decrypting with, which the buffer carries on with from
   * exactly where it left off.
   */
  public Registration register(SocketChannel channel, byte[] carriedPlaintext, javax.crypto.Cipher decrypt,
                               Handler handler) throws IOException {
    if (!running) throw new IOException("the proxy is stopping");
    Loop loop = loops[Math.floorMod(next.getAndIncrement(), loops.length)];
    ChannelReader reader = new ChannelReader(channel);
    reader.seed(carriedPlaintext);
    if (decrypt != null) reader.decryptWith(decrypt);
    channel.configureBlocking(false);
    Registration registration = new Registration(channel, reader, handler, loop);
    loop.add(registration);
    return registration;
  }

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
          return;
        }
        // Once, straight away, whether or not the socket has anything new. A connection arrives here
        // with bytes the blocking stream had already taken off it, and those will never make the
        // socket readable again: waiting for readability to start reading loses whatever the peer
        // had already sent, which for a client that had its first packet in flight is the session.
        hand(registration);
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
          selector.select(SWEEP_INTERVAL_MILLIS);
          applyPending();
          if (!alive) break;
          var ready = selector.selectedKeys().iterator();
          while (ready.hasNext()) {
            SelectionKey key = ready.next();
            ready.remove();
            dispatch(key);
          }
          sweep();
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

    private long sweptAt;

    /**
     * Looks over every watched connection for one whose peer has stopped taking bytes. Only this
     * finds them: such a connection is written to by nobody once backpressure has paused what feeds
     * it, and never becomes writable, so no event of its own is coming.
     */
    private void sweep() {
      long now = System.nanoTime();
      if (now - sweptAt < SWEEP_INTERVAL_NANOS) return;
      sweptAt = now;
      for (SelectionKey key : selector.keys()) {
        if (!(key.attachment() instanceof Registration registration) || registration.done.get()) continue;
        registration.writer.checkProgress();
        String broken = registration.writer.failure();
        if (broken != null) { finish(registration, broken); continue; }
        checkPeerGone(key, registration);
      }
    }

    /**
     * Asks a paused connection whether its peer is still there.
     *
     * <p>A connection held back because what it feeds is congested has no read interest, so the
     * selector would not report it readable even once its peer has hung up: the session would sit
     * there until the write deadline noticed the other half. Reading it here is safe because a
     * paused connection is in no worker's hands -- both the handing out and this run on the selector
     * thread -- and it is bounded by refusing to do it once a fair amount is already buffered.
     */
    private void checkPeerGone(SelectionKey key, Registration registration) {
      if (registration.busy.get() || !key.isValid()) return;
      try {
        if ((key.interestOps() & SelectionKey.OP_READ) != 0) return;
      } catch (CancelledKeyException gone) {
        return;
      }
      if (registration.reader.available() >= PAUSED_READ_CEILING_BYTES) return;
      try {
        if (registration.reader.fill() < 0) finish(registration, "the peer closed the connection");
      } catch (IOException gone) {
        finish(registration, gone.getMessage() == null ? gone.getClass().getSimpleName() : gone.getMessage());
      }
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
        hand(registration);
      } catch (CancelledKeyException gone) {
        finish(registration, "the connection closed");
      }
    }

    /**
     * Gives a connection to a worker. Its read interest is taken away first, so the selector does
     * not hand the same connection to a second worker while the first is still in it and the reader
     * stays a single thread's; the worker puts the interest back when it is done.
     */
    private void hand(Registration registration) {
      SelectionKey key = registration.key;
      if (key == null || !key.isValid() || registration.done.get()) return;
      try {
        key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);
      } catch (CancelledKeyException gone) {
        finish(registration, "the connection closed");
        return;
      }
      if (!registration.busy.compareAndSet(false, true)) return;
      try {
        workers.execute(() -> work(registration));
      } catch (RejectedExecutionException stopping) {
        registration.busy.set(false);
        finish(registration, "the proxy is stopping");
      }
    }

    private void work(Registration registration) {
      String reason = null;
      IN_WORKER.set(Boolean.TRUE);
      try {
        if (registration.done.get()) return;
        if (!registration.handler.onReadable()) reason = "the session ended";
      } catch (IOException gone) {
        reason = gone.getMessage() == null ? gone.getClass().getSimpleName() : gone.getMessage();
      } catch (RuntimeException unexpected) {
        ConduitLog.error("connection worker", unexpected);
        reason = "an error while relaying";
      } finally {
        IN_WORKER.remove();
        registration.busy.set(false);
      }
      if (reason != null) {
        finish(registration, reason);
        return;
      }
      // Back on watch, unless what this feeds is behind: then it waits until that peer has caught
      // up. Level-triggered, so anything that arrived meanwhile is reported straight away rather
      // than waiting for the next byte after it.
      Registration sink = registration.sink;
      if (sink != null && !sink.done.get() && sink.writer.congested()) {
        sink.writer.resumeWhenDrained(() -> want(registration, SelectionKey.OP_READ));
      } else {
        want(registration, SelectionKey.OP_READ);
      }
    }
  }
}
