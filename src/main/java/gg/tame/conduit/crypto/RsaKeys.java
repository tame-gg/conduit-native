// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.crypto;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import javax.crypto.Cipher;

public final class RsaKeys {
  private RsaKeys() { }
  public static KeyPair generate() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(1024, new SecureRandom());
      return generator.generateKeyPair();
    } catch (Exception exception) { throw new IllegalStateException("cannot generate RSA key pair", exception); }
  }
  public static byte[] decrypt(PrivateKey privateKey, byte[] ciphertext) throws Exception {
    if (ciphertext.length == 0 || ciphertext.length > 128) throw new IllegalArgumentException("invalid RSA ciphertext");
    Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
    cipher.init(Cipher.DECRYPT_MODE, privateKey);
    return cipher.doFinal(ciphertext);
  }
  public static byte[] encrypt(java.security.PublicKey publicKey, byte[] plaintext) throws Exception {
    Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
    cipher.init(Cipher.ENCRYPT_MODE, publicKey);
    return cipher.doFinal(plaintext);
  }
}
