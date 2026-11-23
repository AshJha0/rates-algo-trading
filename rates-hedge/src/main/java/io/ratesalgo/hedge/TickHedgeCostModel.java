package io.ratesalgo.hedge;

import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.Instrument;
import io.ratesalgo.api.InstrumentDirectory;
import org.agrona.collections.Int2IntHashMap;

/**
 * Hedge cost per instrument from its own venue tick (review 2026-11-23, Q5), in place of one flat
 * amount per lot for every bond. Crossing a book one tick wide costs half a tick against the mid,
 * so the linear cost of one lot is
 *
 * <pre>
 *   linear(j) = halfSpreadTicks(j) * tick(j) * quoteValue(j) * lotMajor(j) + basis(j)     (currency per lot)
 * </pre>
 *
 * A 1/128 tick on 1mm face of a Treasury is 78.125 USD, so half a tick is 39.06 USD a lot: about
 * 0.21 bp of the 2-year's DV01 and 0.05 bp of the 10-year's. The quadratic impact per lot squared
 * is set per instrument; {@link #squareRootImpactPerLot2} gives the value that matches the
 * calibration's square-root law at a reference size. An instrument with no tick, no lot in the
 * universe or no cost entry costs positive infinity, so the planner never trades it. Reads are
 * allocation-free.
 */
public final class TickHedgeCostModel implements HedgeCostModel {

  private final InstrumentDirectory directory;
  private final HedgeUniverse universe;
  private final VenueTicks ticks;
  private final double defaultHalfSpreadTicks;
  private final Int2IntHashMap slots;
  private final double[] halfSpreadTicks;
  private final double[] basis;
  private final double[] impact;

  /**
   * A model over {@code universe}'s lots with {@code defaultHalfSpreadTicks} for every instrument
   * not set otherwise (0.5 for a book one tick wide), no basis and no impact.
   */
  public TickHedgeCostModel(
      final InstrumentDirectory directory,
      final HedgeUniverse universe,
      final VenueTicks ticks,
      final double defaultHalfSpreadTicks,
      final int capacity) {
    if (!(defaultHalfSpreadTicks >= 0.0)) {
      throw new IllegalArgumentException("half spread ticks " + defaultHalfSpreadTicks);
    }
    this.directory = directory;
    this.universe = universe;
    this.ticks = ticks;
    this.defaultHalfSpreadTicks = defaultHalfSpreadTicks;
    slots = new Int2IntHashMap(capacity * 2, 0.5f, -1);
    halfSpreadTicks = new double[capacity];
    basis = new double[capacity];
    impact = new double[capacity];
  }

  /** Sets one instrument: half-spread in ticks, basis cost per lot and impact per lot squared. */
  public TickHedgeCostModel set(
      final int instrumentId, final double halfSpreadInTicks, final double basisPerLot, final double impactPerLot2) {
    if (!(halfSpreadInTicks >= 0.0) || !(basisPerLot >= 0.0) || !(impactPerLot2 >= 0.0)) {
      throw new IllegalArgumentException(
          "negative cost for " + instrumentId + ": " + halfSpreadInTicks + ", " + basisPerLot + ", " + impactPerLot2);
    }
    int s = slots.get(instrumentId);
    if (s < 0) {
      s = slots.size();
      if (s >= halfSpreadTicks.length) {
        throw new IllegalStateException("cost table capacity " + halfSpreadTicks.length + " exhausted");
      }
      slots.put(instrumentId, s);
    }
    halfSpreadTicks[s] = halfSpreadInTicks;
    basis[s] = basisPerLot;
    impact[s] = impactPerLot2;
    return this;
  }

  /** Value of one tick on one lot of {@code instrumentId}, currency; NaN when unknown. */
  public double tickValuePerLot(final int instrumentId) {
    final Instrument inst = directory.byId(instrumentId);
    final int j = universe.indexOf(instrumentId);
    if (inst == null || j < 0) {
      return Double.NaN;
    }
    final long tick = ticks.tick(inst);
    if (tick <= 0L) {
      return Double.NaN;
    }
    return Decimal9.toDouble(tick) * inst.quoteValue() * inst.major(universe.lotMinor(j));
  }

  @Override
  public double linearPerLot(final int instrumentId) {
    final double tickValue = tickValuePerLot(instrumentId);
    if (Double.isNaN(tickValue)) {
      return Double.POSITIVE_INFINITY;
    }
    final int s = slots.get(instrumentId);
    return s < 0 ? defaultHalfSpreadTicks * tickValue : halfSpreadTicks[s] * tickValue + basis[s];
  }

  @Override
  public double impactPerLot2(final int instrumentId) {
    final int s = slots.get(instrumentId);
    return s < 0 ? 0.0 : impact[s];
  }

  /**
   * The quadratic impact coefficient that matches the square-root law at {@code referenceLots}:
   * a market order of Q lots moves the price by {@code I = y * dailySd * sqrt(Q / dailyVolume)}
   * at its end and pays on average two thirds of that over its quantity, so it costs {@code (2/3)
   * I Q v} with v the value of one price unit on one lot. Setting {@code b Q^2} equal to that at
   * the reference size gives {@code b = (2/3) y dailySd v / sqrt(dailyVolume * referenceLots)}.
   * Above the reference the quadratic overstates the square-root cost, below it understates it.
   * Units: dailySd in price units, dailyVolume and referenceLots in lots.
   */
  public static double squareRootImpactPerLot2(
      final double y,
      final double dailySdPrice,
      final double dailyVolumeLots,
      final double referenceLots,
      final double valuePerPriceUnitPerLot) {
    if (!(y >= 0.0) || !(dailySdPrice >= 0.0) || !(dailyVolumeLots > 0.0) || !(referenceLots > 0.0)
        || !(valuePerPriceUnitPerLot >= 0.0)) {
      throw new IllegalArgumentException("square-root impact inputs");
    }
    return (2.0 / 3.0) * y * dailySdPrice * valuePerPriceUnitPerLot / Math.sqrt(dailyVolumeLots * referenceLots);
  }
}
