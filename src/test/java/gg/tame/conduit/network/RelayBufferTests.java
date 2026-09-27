// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.network;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

/**
 * The two buffers the selector relay is built out of, tested on their own.
 *
 * <p>Everything else that covers them goes through a whole {@link MinecraftProxy} -- a real login, a
 * real backend, a client that stops reading -- which is the right test for the relay as a whole and
 * the wrong one for these. Their edges are the frame limits and the two watermarks, and reaching
 * those through a proxy means 2 MiB of unread traffic and a 256 KiB frame, so the branches that
 * decide whether a connection lives were the branches nothing ran.
 *
 * <p>In the same package as what it tests: both classes are package-private, and widening them for a
 * test would be widening them for everyone.
 */
public final class RelayBufferTests {
  /** What the relay passes as its frame limit; the value itself does not matter to these. */
  private static final int MAX_FRAME = 1024;

  public static void run() throws Exception {
    readerReportsWholeFramesOnly();
    readerLetsTheDecoderRefuseAnOversizedFrame();
    readerLetsTheDecoderRefuseAMalformedLength();
    readerRefusesToBeReadPastItsBuffer();
    readerEndsOnlyOnceItsLastFrameIsTaken();
    readerShrinksAfterALargeFrame();
    writerRefusesToQueuePastItsBound();
    writerReleasesAPausedFeederWhenItFails();
    writerResumesAFeederThatIsAlreadyCaughtUp();
    writerResumesAFeederOnceThePeerReads();
    System.out.println("RelayBufferTests OK");
  }

  /** A frame is complete when its length VarInt and that many bytes behind it are both buffered. */
  private static void readerReportsWholeFramesOnly() {
    ChannelReader reader = seeded(new byte[] {3, 'a', 'b'});
    require(!reader.hasCompleteFrame(MAX_FRAME), "two of three bytes is not a whole frame");
    require(reader.available() == 3, "the partial frame is still buffered: " + reader.available());

    reader = seeded(new byte[] {3, 'a', 'b', 'c'});
    require(reader.hasCompleteFrame(MAX_FRAME), "three of three bytes is a whole frame");

    require(!seeded(new byte[0]).hasCompleteFrame(MAX_FRAME), "nothing buffered is not a whole frame");
    // A length VarInt that is itself cut in half: the first byte has its continuation bit set.
    require(!seeded(new byte[] {(byte) 0x80}).hasCompleteFrame(MAX_FRAME), "half a length is not a whole frame");
    // Zero-length frames exist on the wire; the decoder behind this is what rejects them.
    require(seeded(new byte[] {0}).hasCompleteFrame(MAX_FRAME), "a zero-length frame is complete");
  }

  /**
   * A frame claiming more than the limit is called complete on purpose: the read behind this refuses
   * it with the message it already has, where reporting it incomplete would instead wait forever for
   * bytes the peer may never send.
   */
  private static void readerLetsTheDecoderRefuseAnOversizedFrame() {
    // 2048 as a VarInt, with none of its body: over MAX_FRAME, and complete all the same.
    ChannelReader reader = seeded(new byte[] {(byte) 0x80, 0x10});
    require(reader.hasCompleteFrame(MAX_FRAME), "an oversized frame is handed on to be refused");
    require(!reader.hasCompleteFrame(1 << 20), "under a larger limit the same frame is still incomplete");
  }

  /** A length VarInt running to a fifth byte, and one whose value overflows into a negative int. */
  private static void readerLetsTheDecoderRefuseAMalformedLength() {
    byte[] fiveBytes = {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80};
    require(seeded(fiveBytes).hasCompleteFrame(MAX_FRAME), "a five-byte length is handed on to be refused");
    // 0xFFFFFFFF: a well-formed four-byte VarInt whose value is -1.
    byte[] negative = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x0F};
    require(seeded(negative).hasCompleteFrame(MAX_FRAME), "a negative length is handed on to be refused");
  }

  /**
   * Reading past the buffer is a fault here, not a slow peer, and says so. Reported as an end of
   * stream it would be taken for the peer hanging up and end a healthy session.
   */
  private static void readerRefusesToBeReadPastItsBuffer() throws Exception {
    ChannelReader reader = seeded(new byte[] {'a'});
    require(reader.read() == 'a', "the buffered byte is read");
    try {
      reader.read();
      throw new AssertionError("a read past the buffer must not be reported as an end of stream");
    } catch (IOException expected) {
      require(expected.getMessage().contains("read past the buffered frame"), "named: " + expected.getMessage());
    }
    byte[] into = new byte[4];
    try {
      reader.read(into, 0, 4);
      throw new AssertionError("a bulk read past the buffer must throw too");
    } catch (IOException expected) { }
    require(reader.read(into, 0, 0) == 0, "a zero-length read is answered without touching the buffer");
  }

  /**
   * Whole frames left over are the peer's last words and keep the connection; a fragment does not.
   * Counting a fragment as a reason to stay left a connection readable at end of stream forever,
   * handed to a worker that could make no progress, and a session that never ended.
   */
  private static void readerEndsOnlyOnceItsLastFrameIsTaken() throws Exception {
    try (Pair pair = Pair.connected()) {
      ChannelReader reader = new ChannelReader(pair.left);
      reader.seed(new byte[] {1, 'x', 2, 'y'});          // One whole frame, then a fragment.
      pair.right.close();                                 // The peer hangs up with that in the proxy.
      require(reader.fill() == -1, "the hang-up is seen");
      require(!reader.ended(MAX_FRAME), "a whole frame still buffered keeps the connection");
      reader.read();
      reader.read();                                      // Take the whole frame: 1, 'x'.
      require(reader.ended(MAX_FRAME), "the fragment left behind is not a reason to stay");
      require(reader.available() == 2, "the fragment is still there, simply not relayable");
    }
  }

  /**
   * A buffer grown for one large frame goes back to its starting size once it is empty. Grown only,
   * a proxy full of players who had each once been sent a registry sync held all of those buffers.
   */
  private static void readerShrinksAfterALargeFrame() throws Exception {
    int large = 512 * 1024;                               // Over ChannelReader's SHRINK_ABOVE.
    ChannelReader reader = seeded(new byte[large]);
    require(reader.available() == large, "the whole frame is buffered");
    byte[] into = new byte[large];
    require(reader.read(into, 0, large) == large, "and read back in one go");
    require(reader.available() == 0, "leaving nothing buffered");
    // Grown again from the starting size: a buffer that had not shrunk would take this without a copy,
    // so the only thing to assert is that it still behaves, and that it did not keep the 512 KiB.
    reader.seed(new byte[] {1, 'z'});
    require(reader.hasCompleteFrame(MAX_FRAME), "the shrunk buffer still holds a frame");
  }

  /**
   * Past its bound the writer gives up rather than buffering a stalled peer's traffic on the heap. A
   * thousand connections each holding what a flooding backend sent is the heap, not a slow client.
   */
  private static void writerRefusesToQueuePastItsBound() throws Exception {
    try (Pair pair = Pair.connected()) {
      ChannelWriter writer = new ChannelWriter(pair.left, () -> { });
      byte[] chunk = new byte[64 * 1024];
      try {
        // Never flushed, so nothing reaches the socket and everything is owed: the bound is what
        // stops this, and it is reached in a bounded number of writes.
        for (int written = 0; written <= ChannelWriter.MAX_PENDING_BYTES; written += chunk.length) {
          writer.write(chunk, 0, chunk.length);
        }
        throw new AssertionError("queueing past MAX_PENDING_BYTES must fail the connection");
      } catch (IOException expected) {
        require(expected.getMessage().contains("stopped reading"), "named: " + expected.getMessage());
      }
      require(writer.failure() != null, "the failure is readable, which is how the sweep finds it");
      try {
        writer.write(new byte[] {1}, 0, 1);
        throw new AssertionError("a failed writer must refuse further writes");
      } catch (IOException expected) { }
    }
  }

  /**
   * The regression this file was written for. A writer fails at or above the high mark, so weighing
   * the low mark before releasing the connection that feeds it -- which is what {@code fail} did --
   * skipped the one release it existed to perform, and left that connection paused with its read
   * interest off, waiting on a writer that would never drain again.
   */
  private static void writerReleasesAPausedFeederWhenItFails() throws Exception {
    try (Pair pair = Pair.connected()) {
      ChannelWriter writer = new ChannelWriter(pair.left, () -> { });
      byte[] chunk = new byte[64 * 1024];
      // Congested, but well inside the bound: this is a connection being held back, not a failed one.
      while (!writer.congested()) writer.write(chunk, 0, chunk.length);
      boolean[] resumed = {false};
      writer.resumeWhenDrained(() -> resumed[0] = true);
      require(!resumed[0], "a congested writer holds its feeder back");
      try {
        for (int written = 0; written <= ChannelWriter.MAX_PENDING_BYTES; written += chunk.length) {
          writer.write(chunk, 0, chunk.length);
        }
        throw new AssertionError("the bound must still be enforced");
      } catch (IOException expected) { }
      require(resumed[0], "a writer that has failed must let its feeder be read again, whatever it"
          + " still has queued: nothing will ever drain it, so no watermark it is weighed against"
          + " will ever come down");
    }
  }

  /** Nothing to wait for: a feeder asking about a writer already under the low mark is let straight on. */
  private static void writerResumesAFeederThatIsAlreadyCaughtUp() throws Exception {
    try (Pair pair = Pair.connected()) {
      ChannelWriter writer = new ChannelWriter(pair.left, () -> { });
      writer.write(new byte[] {1, 2, 3}, 0, 3);
      boolean[] resumed = {false};
      writer.resumeWhenDrained(() -> resumed[0] = true);
      require(resumed[0], "an uncongested writer resumes its feeder at once");
      require(!writer.congested(), "and is not congested");
    }
  }

  /** And the ordinary path: the peer reads, the queue falls under the low mark, the feeder goes back on watch. */
  private static void writerResumesAFeederOnceThePeerReads() throws Exception {
    try (Pair pair = Pair.connected()) {
      ChannelWriter writer = new ChannelWriter(pair.left, () -> { });
      byte[] chunk = new byte[64 * 1024];
      while (!writer.congested()) writer.write(chunk, 0, chunk.length);
      boolean[] resumed = {false};
      writer.resumeWhenDrained(() -> resumed[0] = true);
      require(!resumed[0], "held back while the peer is behind");
      // The peer takes everything; each pass hands the socket a chunk, so this is several passes.
      byte[] sink = new byte[chunk.length];
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
      while (!resumed[0] && System.nanoTime() < deadline) {
        writer.checkProgress();
        require(writer.failure() == null, "a peer that is reading must not be given up on: " + writer.failure());
        while (pair.right.read(java.nio.ByteBuffer.wrap(sink)) > 0) { }
      }
      require(resumed[0], "once the peer has caught up its feeder is read again");
    }
  }

  /** A reader over a channel nothing is ever read from, holding only what it was handed. */
  private static ChannelReader seeded(byte[] plaintext) {
    ChannelReader reader = new ChannelReader(null);
    reader.seed(plaintext);
    return reader;
  }

  /** Two connected loopback channels: what a relay has on either side, without a proxy around them. */
  private record Pair(ServerSocketChannel listener, SocketChannel left, SocketChannel right) implements AutoCloseable {
    static Pair connected() throws IOException {
      ServerSocketChannel listener = ServerSocketChannel.open();
      listener.bind(new InetSocketAddress("127.0.0.1", 0));
      SocketChannel left = SocketChannel.open(listener.getLocalAddress());
      SocketChannel right = listener.accept();
      left.configureBlocking(false);
      right.configureBlocking(false);
      return new Pair(listener, left, right);
    }
    @Override public void close() {
      for (java.io.Closeable open : new java.io.Closeable[] {left, right, listener}) {
        try { open.close(); } catch (IOException ignored) { }
      }
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
