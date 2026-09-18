// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.auth;

import java.util.Optional;

public record SessionQuery(String username, String serverHash, Optional<String> clientAddress) {
  public SessionQuery {
    if (username == null || username.isBlank()) throw new IllegalArgumentException("username is required");
    if (serverHash == null || serverHash.isBlank()) throw new IllegalArgumentException("server hash is required");
  }
}
