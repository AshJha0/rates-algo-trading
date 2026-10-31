package io.ratesalgo.posttrade.position;

/**
 * Start-of-day position re-struck at the previous official close (marking policy, API_SPEC.md
 * Section 17.3), so that the day's P&amp;L is measured from the previous official mark.
 *
 * @param quantity signed minor units
 * @param price previous official mark, Decimal9 mantissa
 */
public record OpeningPosition(long quantity, long price) {}
