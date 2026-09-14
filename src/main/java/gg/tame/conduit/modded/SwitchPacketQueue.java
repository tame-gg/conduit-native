package gg.tame.conduit.modded;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Bounded switch-boundary packet queue.
 * Packets have exclusive ownership: CLIENT, OLD_BACKEND, NEW_BACKEND, or DROP.
 */
public final class SwitchPacketQueue {
  public enum Destination { CLIENT, OLD_BACKEND, NEW_BACKEND, DROP }

  public record QueuedPacket(Destination destination, byte[] packet, ConnectionPhase phase) {
    public QueuedPacket {
      if (packet == null) throw new IllegalArgumentException("packet is null");
      // Defensive copy so callers cannot mutate owned buffers after enqueue.
      packet = packet.clone();
    }
  }

  public enum ConnectionPhase { CONFIGURATION, PLAY, OTHER }

  public static final class OverflowException extends RuntimeException {
    public OverflowException(String message) { super(message); }
  }

  private final int capacity;
  private final ArrayDeque<QueuedPacket> queue = new ArrayDeque<>();
  private boolean cancelled;

  public SwitchPacketQueue(int capacity) {
    this.capacity = Math.max(1, capacity);
  }

  public synchronized int size() { return queue.size(); }
  public synchronized int capacity() { return capacity; }
  public synchronized boolean isEmpty() { return queue.isEmpty(); }
  public synchronized boolean isCancelled() { return cancelled; }

  public synchronized void enqueue(Destination destination, byte[] packet, ConnectionPhase phase) {
    if (cancelled) throw new IllegalStateException("queue cancelled");
    if (destination == Destination.DROP) return;
    if (queue.size() >= capacity) {
      throw new OverflowException("switch packet queue exceeded capacity " + capacity);
    }
    queue.addLast(new QueuedPacket(destination, packet, phase));
  }

  public synchronized List<QueuedPacket> flush(Destination destination) {
    List<QueuedPacket> out = new ArrayList<>();
    var iterator = queue.iterator();
    while (iterator.hasNext()) {
      QueuedPacket next = iterator.next();
      if (next.destination() == destination) {
        out.add(next);
        iterator.remove();
      }
    }
    return out;
  }

  public synchronized List<QueuedPacket> drainAll() {
    List<QueuedPacket> out = new ArrayList<>(queue);
    queue.clear();
    return out;
  }

  public synchronized void cancel() {
    cancelled = true;
    queue.clear();
  }

  public synchronized void clear() {
    queue.clear();
  }
}
