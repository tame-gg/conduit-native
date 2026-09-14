package gg.tame.conduit.metrics;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Lightweight counters. Quiet unless requested. Never logs packet contents. */
public final class ConduitMetrics {
  private static final ConduitMetrics INSTANCE = new ConduitMetrics();
  private final AtomicInteger players = new AtomicInteger();
  private final AtomicInteger backends = new AtomicInteger();
  private final LongAdder inboundPackets = new LongAdder();
  private final LongAdder outboundPackets = new LongAdder();
  private final LongAdder inboundBytes = new LongAdder();
  private final LongAdder outboundBytes = new LongAdder();
  private final LongAdder authentications = new LongAdder();
  private final LongAdder decodeFailures = new LongAdder();
  private final LongAdder encodeFailures = new LongAdder();
  private final LongAdder backendConnectNanos = new LongAdder();
  private final LongAdder backendConnects = new LongAdder();
  private final LongAdder switchNanos = new LongAdder();
  private final LongAdder switches = new LongAdder();
  private final AtomicLong windowStart = new AtomicLong(System.nanoTime());
  private final AtomicLong lastInboundPackets = new AtomicLong();
  private final AtomicLong lastOutboundPackets = new AtomicLong();
  private final AtomicLong lastInboundBytes = new AtomicLong();
  private final AtomicLong lastOutboundBytes = new AtomicLong();
  public static ConduitMetrics current() { return INSTANCE; }
  public void playerJoined() { players.incrementAndGet(); }
  public void playerLeft() { players.updateAndGet(value -> Math.max(0, value - 1)); }
  public void backendOpened() { backends.incrementAndGet(); }
  public void backendClosed() { backends.updateAndGet(value -> Math.max(0, value - 1)); }
  public void inbound(int bytes) { inboundPackets.increment(); inboundBytes.add(bytes); }
  public void outbound(int bytes) { outboundPackets.increment(); outboundBytes.add(bytes); }
  public void authentication() { authentications.increment(); }
  public void decodeFailure() { decodeFailures.increment(); }
  public void encodeFailure() { encodeFailures.increment(); }
  public void backendConnect(long nanos) { backendConnects.increment(); backendConnectNanos.add(nanos); }
  public void serverSwitch(long nanos) { switches.increment(); switchNanos.add(nanos); }
  public int activePlayers() { return players.get(); }
  public int activeBackends() { return backends.get(); }
  public long authentications() { return authentications.sum(); }
  public long decodeFailures() { return decodeFailures.sum(); }
  public long encodeFailures() { return encodeFailures.sum(); }
  public Snapshot snapshot() {
    long now = System.nanoTime();
    long previous = windowStart.getAndSet(now);
    double seconds = Math.max(0.001, (now - previous) / (double) TimeUnit.SECONDS.toNanos(1));
    long inP = inboundPackets.sum();
    long outP = outboundPackets.sum();
    long inB = inboundBytes.sum();
    long outB = outboundBytes.sum();
    long dInP = inP - lastInboundPackets.getAndSet(inP);
    long dOutP = outP - lastOutboundPackets.getAndSet(outP);
    long dInB = inB - lastInboundBytes.getAndSet(inB);
    long dOutB = outB - lastOutboundBytes.getAndSet(outB);
    long connects = Math.max(1, backendConnects.sum());
    long switchCount = Math.max(1, switches.sum());
    return new Snapshot(players.get(), backends.get(), dInP / seconds, dOutP / seconds, dInB / seconds, dOutB / seconds,
        authentications.sum(), backendConnectNanos.sum() / connects / 1_000_000.0, switchNanos.sum() / switchCount / 1_000_000.0,
        decodeFailures.sum(), encodeFailures.sum());
  }
  public record Snapshot(int players, int backends, double packetsInPerSec, double packetsOutPerSec,
                         double bytesInPerSec, double bytesOutPerSec, long authentications,
                         double backendConnectMs, double switchMs, long decodeFailures, long encodeFailures) {
    @Override public String toString() {
      return "players=" + players + " backends=" + backends
          + " pkt/s in=" + round(packetsInPerSec) + " out=" + round(packetsOutPerSec)
          + " bytes/s in=" + round(bytesInPerSec) + " out=" + round(bytesOutPerSec)
          + " auth=" + authentications + " backend-connect-ms=" + round(backendConnectMs)
          + " switch-ms=" + round(switchMs) + " decode-fail=" + decodeFailures + " encode-fail=" + encodeFailures;
    }
    private static long round(double value) { return Math.round(value); }
  }
}
