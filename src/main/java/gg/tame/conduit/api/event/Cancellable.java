// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event;

public interface Cancellable {
  boolean cancelled();
  void setCancelled(boolean cancelled);
}
