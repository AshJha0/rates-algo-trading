package io.ratesalgo.api;

/**
 * Sensitivity of a signed quantity of an instrument to the spread between the government curve
 * and the swap curve, as a ladder on {@link KeyRateTenors}: the second set of risk factors beside
 * the swap-rate ladder of {@link SensitivityProvider}.
 *
 * <p>Plain idea. A Treasury is priced off the swap curve plus a spread. Its value moves when swap
 * rates move with the spread held, which {@link SensitivityProvider#pv01} reports, and when the
 * spread moves with swap rates held, which this reports. A swap has no spread sensitivity. A swap
 * hedged with a Treasury therefore nets to about zero on the first ladder and shows the bond's
 * whole spread ladder on the second: swap-spread risk as a factor of its own.
 *
 * <p>Sign and unit: PV(spread + 1bp) minus PV(base) in major currency units at each tenor,
 * negative for a long bond, the convention of {@link Dv01Vector}. A bond's total yield risk is the
 * sum of the two ladders' parallels to first order. Must not allocate.
 */
public interface SpreadSensitivityProvider {

  /**
   * Overwrites {@code out} with the spread ladder of {@code quantityMinor} (signed: positive is
   * long) of the instrument and sets its currency. Returns false for an unknown instrument, in
   * which case {@code out} is left zeroed. A swap returns true with a zero ladder.
   */
  boolean spreadPv01(int instrumentId, long quantityMinor, Dv01Vector out);
}
