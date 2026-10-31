package io.ratesalgo.posttrade.marks;

import io.ratesalgo.posttrade.position.Position;
import io.ratesalgo.posttrade.position.PositionKeeper;
import io.ratesalgo.posttrade.position.ValuationConvention;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Official end-of-day P&amp;L from the keeper's positions and the official marks (marking policy,
 * API_SPEC.md Section 17.3):
 *
 * <ol>
 *   <li>Every position with a non-zero closing quantity needs an official close; a missing mark
 *       stops the run with {@link MissingMarkException}. Flat positions need none.
 *   <li>Realised P&amp;L is the keeper's weighted-average-cost figure, measured from the opening
 *       position's re-strike at the previous official close.
 *   <li>Unrealised P&amp;L revalues the closing quantity from its average cost to today's close.
 *   <li>Carry is the closing quantity times the carry fraction for the date.
 *   <li>Each component is rounded half-even to the minor unit; the total is the sum of rounded
 *       components, so the line reconciles to an attribution line by line.
 * </ol>
 *
 * <p>Intraday P&amp;L (the keeper's realised figure plus a revaluation at a live mid) is an
 * estimate and is never written as official.
 */
public final class EndOfDayPnl {

  private final MarkSource marks;
  private final CarrySource carry;
  private final ValuationConvention convention;

  public EndOfDayPnl(
      final MarkSource marks, final CarrySource carry, final ValuationConvention convention) {
    this.marks = marks;
    this.carry = carry;
    this.convention = convention;
  }

  /** One line per position key, in key order. */
  public List<PnlLine> compute(final LocalDate date, final PositionKeeper keeper) {
    final List<PnlLine> out = new ArrayList<>();
    for (final Position p : keeper.positions()) {
      final int instrument = p.key().instrumentId();
      long mark = 0;
      BigDecimal unrealised = BigDecimal.ZERO;
      BigDecimal carried = BigDecimal.ZERO;
      if (p.netQuantity() != 0) {
        mark = marks.closingMark(instrument, date).orElseThrow(() -> new MissingMarkException(instrument, date));
        unrealised =
            convention.pnl(instrument, p.netQuantity(), p.averagePrice(), BigDecimal.valueOf(mark));
        carried = BigDecimal.valueOf(p.netQuantity()).multiply(carry.carryFraction(instrument, date));
      }
      out.add(
          new PnlLine(
              date, p.key(), p.netQuantity(), mark, p.realisedMinor(),
              Position.roundMinor(unrealised), Position.roundMinor(carried)));
    }
    return out;
  }
}
