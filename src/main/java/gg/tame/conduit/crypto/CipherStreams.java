// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.crypto;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import javax.crypto.Cipher;
import javax.crypto.ShortBufferException;

public final class CipherStreams {
  private CipherStreams() { }
  public static InputStream decrypting(InputStream source, Cipher cipher) { return new DecryptingInputStream(source, cipher); }
  public static OutputStream encrypting(OutputStream destination, Cipher cipher) { return new EncryptingOutputStream(destination, cipher); }

  private static final class DecryptingInputStream extends FilterInputStream {
    private final Cipher cipher;
    private final byte[] one = new byte[1];
    private byte[] scratch = new byte[256];
    private DecryptingInputStream(InputStream in, Cipher cipher) { super(in); this.cipher = cipher; }
    @Override public int read() throws IOException {
      int value = in.read();
      if (value < 0) return -1;
      one[0] = (byte) value;
      try {
        cipher.update(one, 0, 1, one, 0);
      } catch (ShortBufferException exception) {
        throw new IOException(exception);
      }
      return one[0] & 0xff;
    }
    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
      int count = in.read(buffer, offset, length);
      if (count <= 0) return count;
      if (scratch.length < count) scratch = new byte[count];
      try {
        int n = cipher.update(buffer, offset, count, scratch, 0);
        System.arraycopy(scratch, 0, buffer, offset, n);
      } catch (ShortBufferException exception) {
        throw new IOException(exception);
      }
      return count;
    }
  }

  private static final class EncryptingOutputStream extends FilterOutputStream {
    private final Cipher cipher;
    private final byte[] one = new byte[1];
    private byte[] scratch = new byte[256];
    private EncryptingOutputStream(OutputStream out, Cipher cipher) { super(out); this.cipher = cipher; }
    @Override public void write(int value) throws IOException {
      one[0] = (byte) value;
      try {
        cipher.update(one, 0, 1, one, 0);
      } catch (ShortBufferException exception) {
        throw new IOException(exception);
      }
      out.write(one[0] & 0xff);
    }
    @Override public void write(byte[] buffer, int offset, int length) throws IOException {
      if (scratch.length < length) scratch = new byte[length];
      try {
        int n = cipher.update(buffer, offset, length, scratch, 0);
        out.write(scratch, 0, n);
      } catch (ShortBufferException exception) {
        throw new IOException(exception);
      }
    }
    @Override public void flush() throws IOException { out.flush(); }
  }
}
