package io.ratesalgo.posttrade.position;

import io.ratesalgo.posttrade.trade.PositionKey;
import io.ratesalgo.posttrade.trade.Trade;
import java.math.BigDecimal;
import java.util.List;

/**
 * Weighted-average-cost position arithmetic (API_SPEC.md Section 17.2).
 *
 * <p>Trades are applied in transaction-time order. A trade in the direction of the position adds
 * to the cost; a trade against it realises {@code closed * (price - average)} through the
 * valuation convention and removes the closed share of the cost; a trade that crosses zero closes
 * the old position and opens the remainder at its own price.
 */
public final class PositionCalculator {

  private PositionCalculator() {}

  /**
   * Computes one position from its opening and its live trades.
   *
   * @param opening opening quantity re-struck at the previous official mark, or null
   * @param liveTrades live trades of this key, already in transaction-time order
   */
  public static Position compute(
      final PositionKey key,
      final OpeningPosition opening,
      final List<Trade> liveTrades,
      final ValuationConvention convention) {
    final State s = new State(key.instrumentId(), convention);
    if (opening != null && opening.quantity() != 0) {
      s.apply(opening.quantity(), BigDecimal.valueOf(opening.price()));
    }
    for (final Trade t : liveTrades) {
      s.apply(t.signedQuantity(), BigDecimal.valueOf(t.price()));
    }
    final BigDecimal average =
        s.net == 0
            ? BigDecimal.ZERO
            : s.cost.divide(BigDecimal.valueOf(Math.abs(s.net)), InstrumentCatalogue.PRECISION);
    return new Position(key, s.net, average, s.realised, liveTrades.size());
  }

  private static final class State {
    private final int instrumentId;
    private final ValuationConvention convention;
    private long net;
    private BigDecimal cost = BigDecimal.ZERO;
    private BigDecimal realised = BigDecimal.ZERO;

    State(final int instrumentId, final ValuationConvention convention) {
      this.instrumentId = instrumentId;
      this.convention = convention;
    }

    void apply(final long dq, final BigDecimal price) {
      if (net == 0 || Long.signum(net) == Long.signum(dq)) {
        cost = cost.add(price.multiply(BigDecimal.valueOf(Math.abs(dq))));
        net = Math.addExact(net, dq);
        return;
      }
      final long absNet = Math.abs(net);
      final long closed = Math.min(Math.abs(dq), absNet);
      final BigDecimal average = cost.divide(BigDecimal.valueOf(absNet), InstrumentCatalogue.PRECISION);
      realised = realised.add(convention.pnl(instrumentId, Long.signum(net) * closed, average, price));
      final long remaining = Math.abs(dq) - closed;
      if (closed == absNet) {
        net = 0;
        cost = BigDecimal.ZERO;
      } else {
        cost =
            cost.subtract(
                cost.multiply(BigDecimal.valueOf(closed))
                    .divide(BigDecimal.valueOf(absNet), InstrumentCatalogue.PRECISION));
        net = net + Long.signum(dq) * closed;
      }
      if (remaining > 0) {
        net = Long.signum(dq) * remaining;
        cost = price.multiply(BigDecimal.valueOf(remaining));
      }
    }
  }
}
