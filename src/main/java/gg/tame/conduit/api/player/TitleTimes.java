// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.player;

/**
 * How long a title takes to fade in, stays, and takes to fade out, in client ticks (20 a second),
 * which is the unit every protocol carries them in. The vanilla client's defaults are 10, 70 and 20.
 */
public record TitleTimes(int fadeInTicks, int stayTicks, int fadeOutTicks) {
  public TitleTimes {
    if (fadeInTicks < 0 || stayTicks < 0 || fadeOutTicks < 0) {
      throw new IllegalArgumentException("title times cannot be negative: " + fadeInTicks + "/" + stayTicks + "/" + fadeOutTicks);
    }
  }
}
