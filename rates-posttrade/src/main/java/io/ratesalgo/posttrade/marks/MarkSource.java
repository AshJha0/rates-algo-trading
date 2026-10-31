package io.ratesalgo.posttrade.marks;

import java.time.LocalDate;
import java.util.OptionalLong;

/**
 * Official end-of-day marks (API_SPEC.md Section 17.3). The coordinator binds the real source at
 * integration (P1 curve closes for swaps, public settlement prices for bonds and futures);
 * {@link FixtureMarkSource} reads a small CSV of public closes for tests.
 */
@FunctionalInterface
public interface MarkSource {

  /**
   * The official close of one instrument on one business date.
   *
   * @return Decimal9 mantissa in the instrument's quoting convention, or empty when no official
   *     mark exists; the caller must not substitute a guess
   */
  OptionalLong closingMark(int instrumentId, LocalDate date);
}
