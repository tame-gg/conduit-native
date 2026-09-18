// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol;

/** Pass-through translator for DIRECT same-version sessions. */
public final class IdentityTranslator implements ProtocolTranslator {
  public static final IdentityTranslator INSTANCE = new IdentityTranslator();
  private IdentityTranslator() {}
  @Override public byte[] clientToBackend(ConnectionState state, byte[] packet) { return packet; }
  @Override public byte[] backendToClient(ConnectionState state, byte[] packet) { return packet; }
}
