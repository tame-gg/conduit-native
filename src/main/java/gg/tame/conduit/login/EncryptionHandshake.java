// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.login;

import gg.tame.conduit.crypto.RsaKeys;
import gg.tame.conduit.crypto.ServerHash;
import gg.tame.conduit.protocol.ProtocolDefinition;
import java.nio.ByteBuffer;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;

/** Per-login RSA handshake: unique verify token, shared-secret extraction, Minecraft server hash. */
public final class EncryptionHandshake {
  private final KeyPair keys;
  private final byte[] verifyToken;
  private final String serverId;
  public EncryptionHandshake(KeyPair keys) {
    this.keys = keys;
    this.serverId = "";
    this.verifyToken = new byte[4];
    new SecureRandom().nextBytes(this.verifyToken);
  }
  public EncryptionRequest request(ProtocolDefinition protocol) { return EncryptionRequest.create(protocol, keys.getPublic(), verifyToken); }
  public byte[] sharedSecret(ProtocolDefinition protocol, byte[] packet) throws Exception {
    return sharedSecret(protocol, packet, null);
  }
  /** {@code profileKey}: the X.509 key a 1.19-1.19.2 client sent in Login Start, or null. */
  public byte[] sharedSecret(ProtocolDefinition protocol, byte[] packet, byte[] profileKey) throws Exception {
    EncryptionResponse response = EncryptionResponse.decode(protocol, packet);
    byte[] secret = RsaKeys.decrypt(keys.getPrivate(), response.encryptedSharedSecret());
    if (secret.length != 16) throw new IllegalArgumentException("malformed encrypted secret");
    if (response.encryptedVerifyToken() != null) {
      byte[] token = RsaKeys.decrypt(keys.getPrivate(), response.encryptedVerifyToken());
      if (!Arrays.equals(token, verifyToken)) throw new IllegalArgumentException("incorrect verify token");
    } else {
      // A signature with no key from Login Start to check it against proves nothing.
      if (profileKey == null) throw new IllegalArgumentException("signed encryption response without a profile key");
      Signature check = Signature.getInstance("SHA256withRSA");
      check.initVerify(KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(profileKey)));
      check.update(verifyToken);
      check.update(ByteBuffer.allocate(Long.BYTES).putLong(response.salt()).array());
      if (!check.verify(response.signature())) throw new IllegalArgumentException("incorrect verify token signature");
    }
    return secret;
  }
  public String serverHash(byte[] sharedSecret) { return ServerHash.of(serverId, sharedSecret, keys.getPublic()); }
}
