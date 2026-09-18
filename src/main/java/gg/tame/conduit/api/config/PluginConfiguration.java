// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.config;

import java.nio.file.Path;
import java.util.Optional;

/** Plugin-owned configuration. Isolated from proxy conduit.toml. */
public interface PluginConfiguration {
  Path path();
  Optional<String> string(String key);
  String string(String key, String fallback);
}
