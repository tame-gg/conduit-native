// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.crypto;

import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Minecraft stream cipher: AES/CFB8/NoPadding with the 16-byte shared secret as key and IV. */
public final class AesCfb8 {
  private AesCfb8() { }
  public static Cipher encryptor(byte[] sharedSecret) { return cipher(Cipher.ENCRYPT_MODE, sharedSecret); }
  public static Cipher decryptor(byte[] sharedSecret) { return cipher(Cipher.DECRYPT_MODE, sharedSecret); }
  private static Cipher cipher(int mode, byte[] sharedSecret) {
    if (sharedSecret.length != 16) throw new IllegalArgumentException("Minecraft shared secret must be 16 bytes");
    try {
      Cipher cipher = Cipher.getInstance("AES/CFB8/NoPadding");
      SecretKeySpec key = new SecretKeySpec(sharedSecret, "AES");
      cipher.init(mode, key, new IvParameterSpec(sharedSecret));
      return cipher;
    } catch (GeneralSecurityException exception) { throw new IllegalStateException("AES/CFB8 unavailable", exception); }
  }
}
