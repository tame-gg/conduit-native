package gg.tame.conduit.forwarding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/** Secret material; its value is intentionally never exposed through toString. */
public final class ForwardingSecret {
  private final byte[] value;
  private ForwardingSecret(byte[] value) { this.value = value; }
  public static ForwardingSecret load(Path path) throws IOException {
    byte[] bytes = Files.readString(path, StandardCharsets.UTF_8).trim().getBytes(StandardCharsets.UTF_8);
    if (bytes.length == 0) throw new IllegalArgumentException("forwarding secret file is empty");
    return new ForwardingSecret(bytes);
  }
  public String fingerprint() {
    try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value), 0, 6); }
    catch (Exception exception) { throw new IllegalStateException(exception); }
  }
  @Override public String toString() { return "ForwardingSecret[redacted]"; }
}
