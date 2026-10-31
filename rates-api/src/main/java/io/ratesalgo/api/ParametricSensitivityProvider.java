package io.ratesalgo.api;

/**
 * Stub sensitivity provider. Each instrument has a per-major-unit PV01 ladder at the base curve
 * and a convexity coefficient; a parallel curve shift of {@code s} basis points in its currency
 * scales the ladder by {@code (1 - convexity * s)}. This is enough to make PV01 move with the
 * curve so that the risk engine's incremental update can be tested against full revaluation; it
 * is not a pricing model.
 */
public final class ParametricSensitivityProvider implements SensitivityProvider {

  private final InstrumentDirectory directory;
  private final double[][] unitLadders;
  private final double[] convexity;
  private final boolean[] defined;
  private final double[] shiftBp = new double[CurrencyCode.COUNT];
  private final double[] weights = new double[KeyRateTenors.COUNT];

  /** A provider sized for every instrument in {@code directory}. */
  public ParametricSensitivityProvider(final InstrumentDirectory directory) {
    this.directory = directory;
    final int n = directory.size();
    unitLadders = new double[n][KeyRateTenors.COUNT];
    convexity = new double[n];
    defined = new boolean[n];
  }

  /**
   * Defines the instrument's PV01 per major unit as {@code parallelPv01PerUnit} placed at its
   * tenor and split across the neighbouring pillars.
   */
  public void defineAtTenor(
      final int instrumentId, final double parallelPv01PerUnit, final double convexityPerBp) {
    final int i = require(instrumentId);
    KeyRateTenors.split(directory.byIndex(i).tenorYears(), weights);
    for (int k = 0; k < KeyRateTenors.COUNT; k++) {
      unitLadders[i][k] = parallelPv01PerUnit * weights[k];
    }
    convexity[i] = convexityPerBp;
    defined[i] = true;
  }

  /** Defines the instrument's full per-major-unit ladder. */
  public void defineLadder(
      final int instrumentId, final double[] perUnitLadder, final double convexityPerBp) {
    final int i = require(instrumentId);
    if (perUnitLadder.length != KeyRateTenors.COUNT) {
      throw new IllegalArgumentException("ladder length " + perUnitLadder.length);
    }
    System.arraycopy(perUnitLadder, 0, unitLadders[i], 0, KeyRateTenors.COUNT);
    convexity[i] = convexityPerBp;
    defined[i] = true;
  }

  /** Moves every curve in {@code currency} by {@code bp} basis points (cumulative). */
  public void shiftCurve(final CurrencyCode currency, final double bp) {
    shiftBp[currency.index()] += bp;
  }

  /** Current cumulative shift of the currency's curves. */
  public double curveShiftBp(final CurrencyCode currency) {
    return shiftBp[currency.index()];
  }

  @Override
  public boolean pv01(final int instrumentId, final long quantityMinor, final Dv01Vector out) {
    out.clear();
    final int i = directory.indexOf(instrumentId);
    if (i < 0 || !defined[i]) {
      return false;
    }
    final Instrument inst = directory.byIndex(i);
    out.currency(inst.currency());
    final double scale =
        inst.major(quantityMinor) * (1.0 - convexity[i] * shiftBp[inst.currency().index()]);
    for (int k = 0; k < KeyRateTenors.COUNT; k++) {
      out.set(k, unitLadders[i][k] * scale);
    }
    return true;
  }

  private int require(final int instrumentId) {
    final int i = directory.indexOf(instrumentId);
    if (i < 0) {
      throw new IllegalArgumentException("unknown instrument " + instrumentId);
    }
    return i;
  }
}
