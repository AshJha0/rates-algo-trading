package io.ratesalgo.bench;

import org.agrona.DirectBuffer;

/**
 * Hook for the tick-to-wire figure (docs/BENCHMARKS.md, T4 minus T0). The harness calls it on the
 * outbound gateway thread for every Quote it takes off the loop's outbound ring, with the time it
 * read it. The FIX codec session implements it by encoding the Quote to FIX and stamping T4 when
 * the last byte reaches the socket or the simulator; until then the harness reports no tick-to-wire
 * figure.
 */
@FunctionalInterface
public interface TickToWireProbe {

  /** Does nothing; the report's tick-to-wire section stays empty. */
  TickToWireProbe NONE = (buffer, offset, length, readNanos) -> {};

  /**
   * One Quote left the loop.
   *
   * @param buffer the outbound ring's buffer
   * @param offset the SBE header of the Quote
   * @param length header plus body
   * @param readNanos {@code SystemClock.nanoTime()} when the gateway thread read it
   */
  void onOutbound(DirectBuffer buffer, int offset, int length, long readNanos);
}
