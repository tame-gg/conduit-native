package gg.tame.conduit.crypto;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import javax.crypto.Cipher;

public final class CipherStreams {
  private CipherStreams() { }
  public static InputStream decrypting(InputStream source, Cipher cipher) { return new DecryptingInputStream(source, cipher); }
  public static OutputStream encrypting(OutputStream destination, Cipher cipher) { return new EncryptingOutputStream(destination, cipher); }

  private static final class DecryptingInputStream extends FilterInputStream {
    private final Cipher cipher;
    private DecryptingInputStream(InputStream in, Cipher cipher) { super(in); this.cipher = cipher; }
    @Override public int read() throws IOException {
      int value = in.read();
      if (value < 0) return -1;
      byte[] updated = cipher.update(new byte[] {(byte) value});
      return updated[0] & 0xff;
    }
    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
      int count = in.read(buffer, offset, length);
      if (count <= 0) return count;
      byte[] updated = cipher.update(buffer, offset, count);
      System.arraycopy(updated, 0, buffer, offset, count);
      return count;
    }
  }

  private static final class EncryptingOutputStream extends FilterOutputStream {
    private final Cipher cipher;
    private EncryptingOutputStream(OutputStream out, Cipher cipher) { super(out); this.cipher = cipher; }
    @Override public void write(int value) throws IOException { out.write(cipher.update(new byte[] {(byte) value})); }
    @Override public void write(byte[] buffer, int offset, int length) throws IOException { out.write(cipher.update(buffer, offset, length)); }
    @Override public void flush() throws IOException { out.flush(); }
  }
}
