// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.player;

import java.util.Locale;

/**
 * What the client says about itself in its settings packet, which it sends once it is in the game
 * and again whenever the player changes them. A field the client's release does not send holds the
 * vanilla default: main hand right before 1.9, text filtering off before 1.17, server listing allowed
 * before 1.18, all particles before 1.21.2. A 1.7 client says only whether it shows its cape.
 *
 * @param viewDistance the render distance the client asked for, in chunks
 * @param skinParts the displayed skin parts as the protocol's bit field: cape 0x01, jacket 0x02, left
 *     sleeve 0x04, right sleeve 0x08, left trouser leg 0x10, right trouser leg 0x20, hat 0x40
 */
public record ClientSettings(Locale locale, int viewDistance, ChatMode chatMode, boolean chatColors, int skinParts,
                             MainHand mainHand, boolean textFiltering, boolean serverListing, Particles particles) {
  public enum ChatMode { FULL, COMMANDS_ONLY, HIDDEN }
  public enum MainHand { LEFT, RIGHT }
  public enum Particles { ALL, DECREASED, MINIMAL }

  /** What the vanilla client sends by default, for a client that has not sent its own yet. */
  public static ClientSettings defaults() {
    return new ClientSettings(Locale.US, 12, ChatMode.FULL, true, 0x7F, MainHand.RIGHT, false, true, Particles.ALL);
  }
}
