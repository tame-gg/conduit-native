// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.server;

import java.util.List;
import java.util.Objects;

/**
 * A Forge mod list: a client's, or what a server says of itself in the server list. {@code type} is
 * what Forge calls its handshake, {@code "FML"} for 1.7-1.12 and {@code "FML2"} or {@code "FML3"}
 * after that. A version is empty where the wire does not carry one: a 1.13+ client names its mods
 * and nothing more.
 */
public record ModInfo(String type, List<Mod> mods) {
  public record Mod(String id, String version) {
    public Mod {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(version, "version");
    }
  }
  public ModInfo {
    Objects.requireNonNull(type, "type");
    mods = List.copyOf(mods);
  }
}
