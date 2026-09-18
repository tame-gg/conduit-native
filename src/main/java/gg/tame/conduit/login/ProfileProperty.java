// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.login;

import java.util.Optional;

public record ProfileProperty(String name, String value, Optional<String> signature) {
  public ProfileProperty { signature = signature == null ? Optional.empty() : signature; }
}
