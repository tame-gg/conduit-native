// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol.translate;

import gg.tame.conduit.protocol.ProtocolTrace;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Synthesizes a modern Configuration phase when the backend has none (e.g. 1.13).
 *
 * <p>A 1.20.4 client requires Registry Data ({@code minecraft:dimension_type}, etc.),
 * Feature Flags, and Update Tags before Finish Configuration. Sending finish alone
 * leaves the client's registries null and produces NPEs during Join Game / world
 * packets.
 *
 * <p>Packets were captured from a vanilla 1.20.4 server configuration phase and
 * stored as classpath resources under {@code protocol/config765/}.
 */
public final class ConfigurationSynthesizer {
  private static final String INDEX = "/gg/tame/conduit/protocol/config765/index.txt";
  private static final List<byte[]> PACKETS = load();

  private ConfigurationSynthesizer() {}

  /** Uncompressed configuration packets (id + body), excluding Finish. */
  public static List<byte[]> packetsFor(int clientProtocol) {
    if (clientProtocol != 765) {
      return List.of();
    }
    return PACKETS;
  }

  private static List<byte[]> load() {
    try (InputStream indexStream = ConfigurationSynthesizer.class.getResourceAsStream(INDEX)) {
      if (indexStream == null) {
        throw new IllegalStateException("missing configuration index " + INDEX);
      }
      List<byte[]> packets = new ArrayList<>();
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(indexStream, StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          line = line.trim();
          if (line.isEmpty() || line.startsWith("#")) continue;
          String path = "/gg/tame/conduit/protocol/config765/" + line;
          try (InputStream in = ConfigurationSynthesizer.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing " + path);
            packets.add(in.readAllBytes());
          }
        }
      }
      if (packets.isEmpty()) throw new IllegalStateException("empty configuration packet index");
      ProtocolTrace.note("CONFIG SYNTH loaded " + packets.size() + " packets for protocol 765");
      return Collections.unmodifiableList(packets);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot load configuration synthesizer resources", exception);
    }
  }
}
