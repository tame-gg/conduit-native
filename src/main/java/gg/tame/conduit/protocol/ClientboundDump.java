// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Writes every clientbound packet body to a file, length-prefixed, when
 * {@code -Dconduit.dump.clientbound=<path>} is set.
 *
 * <p>This exists for one reason: a client that rejects a packet reports only that it could not
 * read it, several packets after the one that was actually wrong. Having the exact bytes Conduit
 * put on the wire, in order, is the difference between reading the stream back and guessing at it.
 * It is off unless the property is set, and it is not something to leave on.
 */
public final class ClientboundDump {
  private static final Object LOCK = new Object();
  private static volatile OutputStream stream;
  private static volatile boolean attempted;

  private ClientboundDump() {}

  public static void record(byte[] packet) {
    OutputStream out = open();
    if (out == null || packet == null) return;
    synchronized (LOCK) {
      try {
        // A plain 4-byte big-endian length, then the body. Deliberately not Minecraft framing:
        // the reader of this file should not have to share any assumption with the proxy.
        out.write((packet.length >>> 24) & 0xFF);
        out.write((packet.length >>> 16) & 0xFF);
        out.write((packet.length >>> 8) & 0xFF);
        out.write(packet.length & 0xFF);
        out.write(packet);
        out.flush();
      } catch (IOException ignored) {
        // Diagnostics must never break the session they are observing.
      }
    }
  }

  private static OutputStream open() {
    if (stream != null) return stream;
    if (attempted) return null;
    synchronized (LOCK) {
      if (attempted) return stream;
      attempted = true;
      String target = System.getProperty("conduit.dump.clientbound");
      if (target == null || target.isBlank()) return null;
      try {
        stream = new BufferedOutputStream(Files.newOutputStream(Path.of(target),
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE));
      } catch (IOException failure) {
        System.err.println("clientbound dump unavailable: " + failure.getMessage());
      }
      return stream;
    }
  }
}
