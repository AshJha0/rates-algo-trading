package io.ratesalgo.backtest.pnl;

import io.ratesalgo.backtest.Instrument;
import io.ratesalgo.backtest.InstrumentSet;
import java.util.Map;
import java.util.TreeMap;

/**
 * Positions, cash and attribution accumulators per instrument.
 *
 * <p>Two computations run side by side. The position view keeps cash (every trade price, fee,
 * funding and carry flow) and the position, and values the position at the latest mid. The
 * attribution view accumulates the lines of {@link Attribution} as events happen. Because
 * valuation is linear in price, the two agree identically:
 *
 * <pre>
 *   trade q at p, mid m:   cash -= q p v + fee        spread (or hedge) += q (m - p) v,  fees -= fee
 *   mid m -&gt; m':          (value moves by Q (m' - m) v)  inventory += Q (m' - m) v
 *   funding f, carry c:    cash += f + c              funding += f,  carry += c
 *   position P&amp;L = cash + Q m v  =  spread + hedge + inventory + carry + funding + fees
 * </pre>
 *
 * where {@code v} is the instrument's value per unit. The residual is the difference between the
 * two views and is checked to be below one cent on every run (AttributionReconciliationTest).
 */
public final class PositionBook {

  private static final double NANOS_PER_YEAR_360 = 360.0 * 86_400e9;

  private final InstrumentSet instruments;
  private final CarryModel carryModel;
  private final Map<Integer, Line> lines = new TreeMap<>();

  /** Mutable per-instrument state. */
  private static final class Line {
    final Instrument instrument;
    long position;
    double mid;
    boolean hasMid;
    long lastAccrualNanos;
    double cash;
    double spread;
    double hedge;
    double inventory;
    double carry;
    double funding;
    double fees;
    long grossTraded;

    Line(final Instrument instrument) {
      this.instrument = instrument;
    }
  }

  public PositionBook(final InstrumentSet instruments, final CarryModel carryModel) {
    this.instruments = instruments;
    this.carryModel = carryModel;
  }

  private Line line(final int instrumentId) {
    return lines.computeIfAbsent(instrumentId, id -> new Line(instruments.get(id)));
  }

  /** Records a new mid; accrues funding and carry to {@code nowNanos} at the old mid first. */
  public void mark(final int instrumentId, final double mid, final long nowNanos) {
    if (Double.isNaN(mid) || Double.isInfinite(mid)) {
      throw new IllegalArgumentException("mid is not finite for instrument " + instrumentId);
    }
    final Line l = line(instrumentId);
    if (l.hasMid) {
      accrue(l, nowNanos);
      l.inventory += l.position * (mid - l.mid) * l.instrument.valuePerUnit();
    }
    l.mid = mid;
    l.hasMid = true;
    l.lastAccrualNanos = nowNanos;
  }

  /**
   * Books a trade.
   *
   * @param signedQuantity positive when we buy, negative when we sell, minor units
   * @param price trade price in quoted units
   * @param fee fee paid, major currency, non-negative
   * @param hedge true for hedge orders, false for client trades
   */
  public void trade(
      final int instrumentId,
      final long signedQuantity,
      final double price,
      final double fee,
      final boolean hedge,
      final long nowNanos) {
    final Line l = line(instrumentId);
    if (!l.hasMid) {
      throw new IllegalStateException(
          "trade in instrument " + instrumentId + " before any mid is known");
    }
    if (fee < 0) {
      throw new IllegalArgumentException("fee must be non-negative: " + fee);
    }
    accrue(l, nowNanos);
    final double v = l.instrument.valuePerUnit();
    final double edge = signedQuantity * (l.mid - price) * v;
    if (hedge) {
      l.hedge += edge;
    } else {
      l.spread += edge;
    }
    l.cash -= signedQuantity * price * v + fee;
    l.fees -= fee;
    l.position += signedQuantity;
    l.grossTraded += Math.abs(signedQuantity);
  }

  /** Accrues funding and carry for every instrument up to {@code nowNanos} (end of session). */
  public void close(final long nowNanos) {
    for (final Line l : lines.values()) {
      if (l.hasMid) {
        accrue(l, nowNanos);
      }
    }
  }

  private void accrue(final Line l, final long nowNanos) {
    final long dt = nowNanos - l.lastAccrualNanos;
    if (dt <= 0 || l.position == 0) {
      l.lastAccrualNanos = Math.max(l.lastAccrualNanos, nowNanos);
      return;
    }
    final double value = l.position * l.mid * l.instrument.valuePerUnit();
    final double f = -value * l.instrument.fundingRate() * (dt / NANOS_PER_YEAR_360);
    final double c = carryModel.carry(l.instrument, l.position, l.mid, l.lastAccrualNanos, nowNanos);
    l.funding += f;
    l.carry += c;
    l.cash += f + c;
    l.lastAccrualNanos = nowNanos;
  }

  public long position(final int instrumentId) {
    final Line l = lines.get(instrumentId);
    return l == null ? 0L : l.position;
  }

  public boolean hasMid(final int instrumentId) {
    final Line l = lines.get(instrumentId);
    return l != null && l.hasMid;
  }

  public double mid(final int instrumentId) {
    final Line l = lines.get(instrumentId);
    if (l == null || !l.hasMid) {
      throw new IllegalStateException("no mid yet for instrument " + instrumentId);
    }
    return l.mid;
  }

  /** Net DV01 in major currency per 1 bp rise, across every instrument. */
  public double netDv01() {
    double sum = 0;
    for (final Line l : lines.values()) {
      sum += l.position * l.instrument.dv01PerUnit();
    }
    return sum;
  }

  public long grossTraded(final int instrumentId) {
    final Line l = lines.get(instrumentId);
    return l == null ? 0L : l.grossTraded;
  }

  /** Attribution for one instrument with the residual against its position-based P&amp;L. */
  public Attribution attribution(final int instrumentId) {
    final Line l = lines.get(instrumentId);
    if (l == null) {
      return Attribution.ZERO;
    }
    final double positionPnl =
        l.cash + (l.hasMid ? l.position * l.mid * l.instrument.valuePerUnit() : 0.0);
    final double explained = l.spread + l.inventory + l.hedge + l.carry + l.funding + l.fees;
    return new Attribution(
        l.spread, l.inventory, l.hedge, l.carry, l.funding, l.fees, positionPnl - explained, positionPnl);
  }

  /** Attribution summed over instruments. */
  public Attribution total() {
    Attribution t = Attribution.ZERO;
    for (final Integer id : lines.keySet()) {
      t = t.plus(attribution(id));
    }
    return t;
  }

  /** Ids of instruments the book has seen, ascending. */
  public java.util.List<Integer> instrumentIds() {
    return java.util.List.copyOf(lines.keySet());
  }
}
