// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.login;

import gg.tame.conduit.crypto.RsaKeys;
import gg.tame.conduit.crypto.ServerHash;
import gg.tame.conduit.protocol.ProtocolDefinition;
import java.security.KeyPair;
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
    EncryptionResponse response = EncryptionResponse.decode(protocol, packet);
    byte[] secret = RsaKeys.decrypt(keys.getPrivate(), response.encryptedSharedSecret());
    byte[] token = RsaKeys.decrypt(keys.getPrivate(), response.encryptedVerifyToken());
    if (secret.length != 16) throw new IllegalArgumentException("malformed encrypted secret");
    if (!Arrays.equals(token, verifyToken)) throw new IllegalArgumentException("incorrect verify token");
    return secret;
  }
  public String serverHash(byte[] sharedSecret) { return ServerHash.of(serverId, sharedSecret, keys.getPublic()); }
}
