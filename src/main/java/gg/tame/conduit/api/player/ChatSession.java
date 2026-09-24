// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.player;

import java.util.Objects;
import java.util.UUID;

/**
 * A client's chat key as the client sent it: the public key it signs chat with, when that key expires,
 * and Mojang's signature over it. A 1.19 to 1.19.2 client sends it in its Login Start and has no
 * session id; a 1.19.3+ client sends it once in the game, with a session id of its own. The proxy
 * keeps it as sent and checks nothing about it; a backend that enforces secure chat does.
 *
 * @param sessionId    the 1.19.3+ chat session's id; null for a 1.19 to 1.19.2 key
 * @param expiresAt    when the key expires, in milliseconds since the epoch
 * @param publicKey    the RSA public key, X.509 encoded
 * @param keySignature Mojang's signature over the key
 */
public record ChatSession(UUID sessionId, long expiresAt, byte[] publicKey, byte[] keySignature) {
  public ChatSession {
    publicKey = Objects.requireNonNull(publicKey, "publicKey").clone();
    keySignature = Objects.requireNonNull(keySignature, "keySignature").clone();
  }
  @Override public byte[] publicKey() { return publicKey.clone(); }
  @Override public byte[] keySignature() { return keySignature.clone(); }
}
