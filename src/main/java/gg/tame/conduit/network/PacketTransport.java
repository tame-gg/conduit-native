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
import java.net.Socket;
import javax.crypto.Cipher;

/** Raw socket framing with an optional AES/CFB8 layer applied to the entire byte stream. */
public final class PacketTransport {
  private final Socket socket;
  private InputStream input;
  private OutputStream output;
  private EncryptionState state = EncryptionState.PLAINTEXT;
  private final Object writeLock = new Object();
  public PacketTransport(Socket socket) throws IOException {
    this.socket = socket;
    socket.setTcpNoDelay(true);
    this.input = socket.getInputStream();
    this.output = new BufferedOutputStream(DeadlineOutputStream.of(socket), 8192);
  }
  public PacketTransport(InputStream input, OutputStream output) { this.socket = null; this.input = input; this.output = output; }
  /** Bounds a read that would otherwise park forever; 0 waits indefinitely. */
  public void setReadTimeoutMillis(int millis) throws IOException { if (socket != null) socket.setSoTimeout(millis); }
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
    state = EncryptionState.ENCRYPTED;
  }
  public InputStream input() { return input; }
  public OutputStream output() { return output; }
  /**
   * Ends the connection, not merely the session's view of it.
   *
   * <p>A session that closes itself — a backend that dropped with nowhere to fall back to, a
   * translator that failed, a plugin disconnecting a player — is the only thing that ever calls
   * this. Marking the state and leaving the socket open left the client reader parked in a read
   * nothing would complete, so the worker never returned, and its connection slot and its
   * per-source throttle lease were both held until the client itself hung up.
   */
  public void close() {
    state = EncryptionState.CLOSED;
    if (socket != null) { try { socket.close(); } catch (IOException ignored) { } }
  }
}
