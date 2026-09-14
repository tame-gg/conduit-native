package gg.tame.conduit.api.event;

public interface Cancellable {
  boolean cancelled();
  void setCancelled(boolean cancelled);
}
