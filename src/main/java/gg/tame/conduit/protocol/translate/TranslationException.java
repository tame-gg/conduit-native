// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol.translate;

/** Clean failure when a packet cannot be represented across versions. */
public final class TranslationException extends RuntimeException {
  public TranslationException(String message) { super(message); }
  public TranslationException(String message, Throwable cause) { super(message, cause); }
}
