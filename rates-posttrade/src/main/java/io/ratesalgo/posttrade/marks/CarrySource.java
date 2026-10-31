package io.ratesalgo.posttrade.marks;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Carry accrued on a held position over one business date (API_SPEC.md Section 17.3).
 *
 * <p>The answer is a fraction of quantity: a long bond position of quantity {@code q} carries
 * {@code q * fraction} minor units (coupon accrual less repo financing, over the calendar days to
 * the next business day). For a swap it is the fixed-minus-floating accrual from the payer's side.
 * P1 analytics bind the real computation; {@link FixtureCarrySource} reads a CSV.
 */
@FunctionalInterface
public interface CarrySource {

  /** The carry fraction for one instrument and date; zero when the source has none. */
  BigDecimal carryFraction(int instrumentId, LocalDate date);
}
