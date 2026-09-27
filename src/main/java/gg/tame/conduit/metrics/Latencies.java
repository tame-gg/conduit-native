// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.metrics;

import java.util.concurrent.atomic.LongAdder;

/**
 * How long something took, kept as a distribution rather than only a total.
 *
 * <p>A sum and a count answer what a backend connect costs on average, which is the one question that
 * does not matter: an average hides the joins that took four seconds behind the thousand that took
 * twenty milliseconds, and it is the slow ones a player notices and an operator is asked about. Fixed
 * buckets answer "how many were over a second" and let Prometheus answer a quantile.
 *
 * <p>Buckets, not reservoir sampling or a sketch: a bucket is a {@link LongAdder} increment on a path
 * that already does real work, the memory is the same however many observations arrive, and
 * Prometheus aggregates buckets across instances where it cannot aggregate quantiles.
 */
public final class Latencies {
  /**
   * Bucket edges in seconds, for what a proxy waits on: a backend on the same machine answers inside
   * a millisecond, one across a datacentre inside ten, and anything past a second is the class of
   * event worth alerting on. The last edge is deliberately past every reasonable timeout, so a bucket
   * count that stops growing below it means the timeouts themselves are what to look at.
   */
  static final double[] BOUNDS = {0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10};

  private final LongAdder[] buckets = new LongAdder[BOUNDS.length];
  private final LongAdder count = new LongAdder();
  private final LongAdder totalNanos = new LongAdder();

  Latencies() {
    for (int index = 0; index < buckets.length; index++) buckets[index] = new LongAdder();
  }

  /** Records one observation. Cumulative, as Prometheus' histogram buckets are. */
  public void record(long nanos) {
    count.increment();
    totalNanos.add(nanos);
    double seconds = nanos / 1e9;
    for (int index = 0; index < BOUNDS.length; index++) {
      if (seconds <= BOUNDS[index]) buckets[index].increment();
    }
  }

  /** Observations at or under {@code BOUNDS[index]}. */
  long upTo(int index) { return buckets[index].sum(); }
  long count() { return count.sum(); }
  double seconds() { return totalNanos.sum() / 1e9; }
}
