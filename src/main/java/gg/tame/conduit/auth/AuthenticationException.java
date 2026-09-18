// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.auth;

public final class AuthenticationException extends Exception {
  public AuthenticationException(String message) { super(message); }
  public AuthenticationException(String message, Throwable cause) { super(message, cause); }
}
