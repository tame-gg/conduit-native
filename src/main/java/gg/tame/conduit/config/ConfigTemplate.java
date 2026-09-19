// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The {@code conduit.toml} Conduit ships, read from the jar.
 *
 * <p>It is the single copy: the file beside the jar, the one a first start writes and the layout a
 * later version rewrites an operator's file into all come from here. A second copy in the source
 * tree drifted from this one, and whichever was stale was the one someone got.
 */
public final class ConfigTemplate {
  private static final String RESOURCE = "/gg/tame/conduit/config/conduit.toml";

  private ConfigTemplate() {}

  /** The shipped configuration, verbatim. */
  public static String text() throws IOException {
    try (InputStream stream = ConfigTemplate.class.getResourceAsStream(RESOURCE)) {
      if (stream == null) {
        // Only reachable from a build that lost its resources; saying which file is missing is the
        // difference between a one-line fix and a hunt.
        throw new IOException("this build is missing its bundled configuration (" + RESOURCE + ")");
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /**
   * The schema the shipped configuration is written for, which is what an operator's file is brought
   * up to. Taken from the template rather than from a constant so the two cannot disagree.
   */
  public static int schemaVersion() throws IOException {
    return schemaVersionOf(text().lines().toList());
  }

  /** The {@code [ops] schema-version} in these lines, or 0 when there is none. */
  static int schemaVersionOf(java.util.List<String> lines) {
    String section = "";
    for (String raw : lines) {
      String line = raw.strip();
      if (line.isEmpty() || line.startsWith("#")) continue;
      if (line.startsWith("[") && line.endsWith("]")) {
        section = line.substring(1, line.length() - 1);
        continue;
      }
      if (!section.equals("ops")) continue;
      int equals = line.indexOf('=');
      if (equals < 1 || !line.substring(0, equals).strip().equals("schema-version")) continue;
      try {
        return Integer.parseInt(line.substring(equals + 1).strip());
      } catch (NumberFormatException notANumber) {
        return 0;
      }
    }
    return 0;
  }
}
