// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.routing;

import gg.tame.conduit.config.BackendServer;
import java.util.List;
import java.util.Optional;

public record ServerMatch(Kind kind, Optional<BackendServer> server, List<String> candidates) {
  public static ServerMatch unique(BackendServer server) { return new ServerMatch(Kind.UNIQUE, Optional.of(server), List.of(server.name())); }
  public static ServerMatch ambiguous(List<String> names) { return new ServerMatch(Kind.AMBIGUOUS, Optional.empty(), List.copyOf(names)); }
  public static ServerMatch none() { return new ServerMatch(Kind.NONE, Optional.empty(), List.of()); }
  public enum Kind { UNIQUE, AMBIGUOUS, NONE }
}
