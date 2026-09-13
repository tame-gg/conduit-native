package gg.tame.conduit.crypto;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;

/** Minecraft's signed SHA-1 server hash ( twos-complement hex, not a plain unsigned digest ). */
public final class ServerHash {
  private ServerHash() { }
  public static String of(String serverId, byte[] sharedSecret, PublicKey publicKey) {
    return of(serverId.getBytes(StandardCharsets.ISO_8859_1), sharedSecret, publicKey.getEncoded());
  }
  public static String of(byte[]... parts) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-1");
      for (byte[] part : parts) digest.update(part);
      return new BigInteger(digest.digest()).toString(16);
    } catch (Exception exception) { throw new IllegalStateException("SHA-1 unavailable", exception); }
  }
}
