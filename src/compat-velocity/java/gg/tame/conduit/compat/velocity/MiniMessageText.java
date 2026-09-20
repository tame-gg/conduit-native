// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.compat.velocity;

import gg.tame.conduit.api.text.Text;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * MiniMessage as Conduit Text.
 *
 * <p>Adventure ships with the Velocity plugin runtime, which core deliberately does not compile
 * against, so the config reader reaches this through {@link gg.tame.conduit.text.MiniMessages}
 * rather than importing MiniMessage itself. Whatever MiniMessage builds -- colours, gradients,
 * decorations, hovers, clicks -- comes back through the same {@link Texts} bridge a plugin's own
 * components do, so a MOTD carries exactly what a plugin message would.
 */
public final class MiniMessageText {
  private MiniMessageText() {}

  /** @throws RuntimeException as MiniMessage throws it, for the caller to fall back on */
  public static Text parse(String raw) {
    return Texts.toConduit(MiniMessage.miniMessage().deserialize(raw));
  }
}
