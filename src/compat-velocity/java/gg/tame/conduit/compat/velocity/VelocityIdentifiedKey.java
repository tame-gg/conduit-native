// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.compat.velocity;

import com.velocitypowered.api.proxy.crypto.IdentifiedKey;
import com.velocitypowered.api.proxy.player.ChatSession;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.UUID;

/**
 * A client's chat key as Velocity's IdentifiedKey: the key the client signs its chat with, and the
 * expiry and Mojang signature it came with.
 *
 * <p>The proxy does not hold Mojang's public key, so it cannot say who signed this one: {@link #getSigner}
 * is null and {@link #isSignatureValid} false, as KeySigned has them for a key that was not checked. A
 * backend that enforces secure chat checks the key itself.
 */
final class VelocityIdentifiedKey implements IdentifiedKey {
  private final PublicKey key;
  private final byte[] signature;
  private final Instant expiry;
  private final UUID holder;
  private final Revision revision;

  private VelocityIdentifiedKey(PublicKey key, byte[] signature, Instant expiry, UUID holder, Revision revision) {
    this.key = key; this.signature = signature; this.expiry = expiry; this.holder = holder; this.revision = revision;
  }

  /**
   * Conduit's chat key as Velocity's, held by {@code holder}; null for none, or for a key that is not
   * an X.509 RSA key. 1.19's keys are GENERIC_V1; 1.19.1's and every later release's, whose signature
   * covers the holder's UUID, LINKED_V2.
   */
  static VelocityIdentifiedKey of(gg.tame.conduit.api.player.ChatSession session, UUID holder, int protocol) {
    if (session == null) return null;
    try {
      PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(session.publicKey()));
      Revision revision = protocol == com.velocitypowered.api.network.ProtocolVersion.MINECRAFT_1_19.getProtocol() ? Revision.GENERIC_V1 : Revision.LINKED_V2;
      return new VelocityIdentifiedKey(key, session.keySignature(), Instant.ofEpochMilli(session.expiresAt()), holder, revision);
    } catch (GeneralSecurityException unreadable) {
      return null;
    }
  }

  /** Velocity's key as Conduit's chat key, with this session id; the key must be X.509 encoded, as a client's is. */
  static gg.tame.conduit.api.player.ChatSession toConduit(UUID sessionId, IdentifiedKey key) {
    return new gg.tame.conduit.api.player.ChatSession(sessionId, key.getExpiryTemporal().toEpochMilli(),
        key.getSignedPublicKey().getEncoded(), key.getSignature());
  }

  @Override public PublicKey getSignedPublicKey() { return key; }
  /** Whether {@code signature} is this key's RSA signature (SHA-256) over {@code toVerify}, taken in order. */
  @Override public boolean verifyDataSignature(byte[] signature, byte[]... toVerify) {
    try {
      Signature verifier = Signature.getInstance("SHA256withRSA");
      verifier.initVerify(key);
      for (byte[] part : toVerify) verifier.update(part);
      return verifier.verify(signature);
    } catch (GeneralSecurityException invalid) {
      return false;
    }
  }
  @Override public UUID getSignatureHolder() { return holder; }
  @Override public Revision getKeyRevision() { return revision; }
  /** Null: Mojang's key signed this one, and the proxy does not have Mojang's key. */
  @Override public PublicKey getSigner() { return null; }
  @Override public Instant getExpiryTemporal() { return expiry; }
  @Override public byte[] getSignature() { return signature.clone(); }

  /** A 1.19.3+ chat session: its id and key. */
  record Session(UUID getSessionId, IdentifiedKey getIdentifiedKey) implements ChatSession {
    static Session of(gg.tame.conduit.api.player.ChatSession session, UUID holder, int protocol) {
      if (session == null || session.sessionId() == null) return null;
      VelocityIdentifiedKey key = VelocityIdentifiedKey.of(session, holder, protocol);
      return key == null ? null : new Session(session.sessionId(), key);
    }
  }
}
