// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.network;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import javax.crypto.Cipher;
import javax.crypto.ShortBufferException;

/**
 * A non-blocking channel read as an {@link InputStream}, with whole frames the only thing anyone
 * ever reads out of it.
 *
 * <p>A socket watched by a {@link ConnectionSelector} is non-blocking, so a read of it can no
 * longer wait for the rest of a packet: there is no thread parked on it to wait. This buffers what
 * the channel has, and {@link #hasCompleteFrame()} says whether a whole Minecraft frame is in
 * there. A caller reads only when it says yes, and the read then finishes out of the buffer without
 * ever touching the channel. Nothing waits, so nothing needs a thread of its own to wait on.
 *
 * <p>Decryption happens here rather than in a stream above, because the frame length is the first
 * thing inside the encrypted stream: a buffer of ciphertext cannot be asked whether it holds a
 * whole frame. AES/CFB8 is a byte-for-byte stream cipher applied in order, so decrypting each
 * chunk as it arrives gives exactly the bytes the stream above used to produce, and the buffer
 * holds plaintext.
 *
 * <p>Not thread-safe, and does not need to be: the selector hands a connection to one worker at a
 * time and takes its read interest away for as long as that worker holds it, so only that one
 * thread is ever in here.
 */
final class ChannelReader extends InputStream {
  /** Enough for the largest frame a client sends in ordinary play, grown when one is not. */
  private static final int INITIAL_CAPACITY = 8192;
  private static final int SCRATCH_BYTES = 16384;

  private final SocketChannel channel;
  private final ByteBuffer scratch = ByteBuffer.allocate(SCRATCH_BYTES);
  private byte[] buffer = new byte[INITIAL_CAPACITY];
  /** Plaintext is {@code buffer[head..tail)}. */
  private int head;
  private int tail;
  private Cipher decrypt;
  private boolean ended;

  ChannelReader(SocketChannel channel) { this.channel = channel; }

  /**
   * Plaintext the stream this replaced had already taken off the socket, which would otherwise be
   * lost at the moment a connection moves onto the selector.
   */
  void seed(byte[] plaintext) {
    if (plaintext.length == 0) return;
    ensure(plaintext.length);
    System.arraycopy(plaintext, 0, buffer, tail, plaintext.length);
    tail += plaintext.length;
  }

  /** The cipher the login negotiated, from the moment the connection moves onto the selector. */
  void decryptWith(Cipher cipher) { this.decrypt = cipher; }

  /** Whether the peer has closed its end and everything it sent has been read. */
  boolean ended() { return ended && head == tail; }

  /**
   * Takes whatever the channel has right now. Returns the number of bytes added, 0 when the channel
   * had none, and -1 once the peer has hung up.
   */
  int fill() throws IOException {
    if (ended) return -1;
    scratch.clear();
    int count = channel.read(scratch);
    if (count < 0) { ended = true; return -1; }
    if (count == 0) return 0;
    scratch.flip();
    ensure(count);
    scratch.get(buffer, tail, count);
    if (decrypt != null) {
      try {
        // In place: CFB8 turns each byte into exactly one byte, so the plaintext is the same length
        // and sits where the ciphertext was.
        decrypt.update(buffer, tail, count, buffer, tail);
      } catch (ShortBufferException impossible) {
        throw new IOException(impossible);
      }
    }
    tail += count;
    return count;
  }

  /**
   * Whether a whole frame is buffered: its length VarInt, and that many bytes behind it.
   *
   * <p>The length is read without consuming it, and a frame claiming more than the configured limit
   * is reported complete so that the read behind this can be the one to refuse it, with the message
   * it already has for that.
   */
  boolean hasCompleteFrame(int maximumFrameBytes) {
    int length = 0;
    int shift = 0;
    int at = head;
    while (true) {
      if (at == tail) return false;
      byte piece = buffer[at++];
      length |= (piece & 0x7f) << shift;
      if ((piece & 0x80) == 0) break;
      shift += 7;
      // A fifth byte means a malformed VarInt; the read behind this rejects it, so let it through.
      if (shift > 28) return true;
    }
    if (length < 0 || length > maximumFrameBytes) return true;
    return tail - at >= length;
  }

  /**
   * Fills until a whole frame is buffered, and answers whether there is one.
   *
   * <p>False means the socket has nothing more to give this time round, not that the peer is gone;
   * {@link #ended()} tells those apart. The look-ahead is one frame and stops there, so a dispatch
   * cannot drain a flooding peer indefinitely: whatever is left makes the connection readable
   * again, and the pause for a congested sink is weighed before it is read once more.
   */
  boolean nextFrameReady(int maximumFrameBytes) throws IOException {
    while (!hasCompleteFrame(maximumFrameBytes)) {
      if (fill() <= 0) return false;
    }
    return true;
  }

  /** Buffered bytes, which is what {@code available()} on the socket stream used to answer. */
  @Override public int available() { return tail - head; }

  @Override public int read() throws IOException {
    if (head == tail) return starved();
    return buffer[head++] & 0xff;
  }

  @Override public int read(byte[] destination, int offset, int length) throws IOException {
    if (length == 0) return 0;
    if (head == tail) return starved();
    int count = Math.min(length, tail - head);
    System.arraycopy(buffer, head, destination, offset, count);
    head += count;
    return count;
  }

  /**
   * A read that ran past what was buffered.
   *
   * <p>Deliberately not a refill. A worker fills once and then relays the whole frames that brought,
   * and anything still on the socket is a second dispatch -- which is where backpressure is
   * weighed. Refilling here instead would let one dispatch drain a flooding server for as long as
   * it kept sending, queueing all of it for a client that may be reading far slower.
   *
   * <p>Callers read only whole frames, so reaching this is a bug here rather than a peer being
   * slow, and it says so instead of reporting an end of stream that would be taken for a hang-up.
   */
  private int starved() throws IOException {
    if (ended) return -1;
    throw new IOException("read past the buffered frame on a non-blocking connection");
  }

  /** Drops what has been read, so a long-lived connection does not walk its buffer forwards forever. */
  private void compact() {
    if (head == 0) return;
    if (head == tail) { head = tail = 0; return; }
    System.arraycopy(buffer, head, buffer, 0, tail - head);
    tail -= head;
    head = 0;
  }

  private void ensure(int extra) {
    if (tail + extra <= buffer.length) return;
    compact();
    if (tail + extra <= buffer.length) return;
    int capacity = buffer.length;
    while (capacity < tail + extra) capacity <<= 1;
    buffer = java.util.Arrays.copyOf(buffer, capacity);
  }
}
