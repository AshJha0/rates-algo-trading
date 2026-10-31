package io.ratesalgo.analytics.date;

/**
 * Where an irregular period goes when the term is not a whole number of periods.
 *
 * <ul>
 *   <li>{@link #SHORT_FRONT}: dates rolled backward from the termination date; the first period is
 *       short. The market default for swaps and bonds (QuantLib {@code DateGeneration::Backward}).
 *   <li>{@link #LONG_FRONT}: as short front, then the short first period is merged into the
 *       second.
 *   <li>{@link #SHORT_BACK}: dates rolled forward from the effective date; the last period is
 *       short (QuantLib {@code DateGeneration::Forward}).
 *   <li>{@link #LONG_BACK}: as short back, then the short last period is merged into the one
 *       before it.
 * </ul>
 */
public enum StubRule {
  SHORT_FRONT,
  LONG_FRONT,
  SHORT_BACK,
  LONG_BACK
}
