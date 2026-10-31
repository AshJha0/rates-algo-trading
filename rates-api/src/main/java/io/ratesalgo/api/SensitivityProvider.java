package io.ratesalgo.api;

/**
 * Key-rate PV01 of a signed quantity of an instrument at the current curves, from P1's risk
 * analytics. The coordinator adapts P1's {@code Dv01Ladder} (currency, pillar tenors, per-pillar
 * DV01, parallel DV01) to {@link Dv01Vector}, re-bucketing to {@link KeyRateTenors} if P1's
 * pillars differ, and flipping sign if P1 reports DV01 as a positive number for a long bond.
 *
 * <p>Must not allocate. {@link ParametricSensitivityProvider} is the stub.
 */
public interface SensitivityProvider {

  /**
   * Overwrites {@code out} with the PV01 ladder of {@code quantityMinor} (signed: positive is
   * long) of the instrument, and sets its currency. Returns false if the instrument is unknown or
   * its curve is not built, in which case {@code out} is left zeroed.
   */
  boolean pv01(int instrumentId, long quantityMinor, Dv01Vector out);
}
