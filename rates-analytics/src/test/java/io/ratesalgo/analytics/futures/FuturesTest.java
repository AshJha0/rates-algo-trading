package io.ratesalgo.analytics.futures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.analytics.bond.BondConvention;
import io.ratesalgo.analytics.bond.BondPricer;
import io.ratesalgo.analytics.bond.FixedRateBond;
import io.ratesalgo.analytics.date.Dates;
import java.util.List;
import org.junit.jupiter.api.Test;

class FuturesTest {

  @Test
  void cmeFormulaEqualsTheSixPercentPriceOfTheTruncatedBond() {
    // the definition: clean price at 6% of a bond whose maturity is rounded down to the quarter
    final BondPricer p = new BondPricer();
    final int first = Dates.of(2025, 3, 1);
    for (final double c : new double[] {0.01375, 0.0275, 0.04125, 0.06, 0.07625}) {
      final int maturity = Dates.of(2032, 8, 15);
      final double cme = ConversionFactors.cme(c, maturity, 2025, 3, TreasuryFutureContract.TY);
      // 7 years 5 months, rounded down to 7 years 3 months
      final int truncated = Dates.addMonths(first, 87);
      final FixedRateBond b = new FixedRateBond("t", BondConvention.UST, c,
          Dates.addMonths(truncated, -96), truncated);
      final double price = p.cleanPrice(b, 0.06, first) / 100.0;
      assertEquals(cme, price, 6e-4, "coupon " + c);
    }
    assertEquals(1.0, ConversionFactors.cme(0.06, Dates.of(2035, 3, 1), 2025, 3,
        TreasuryFutureContract.TY), 1e-12);
    assertEquals(2, ConversionFactors.wholeMonths(Dates.of(2025, 1, 31), Dates.of(2025, 4, 1)));
  }

  @Test
  void contractsAndBaskets() {
    final TreasuryFutureContract ty = TreasuryFutureContract.TY;
    assertTrue(ty.isDeliverable(90, 120));
    assertFalse(ty.isDeliverable(100, 120));
    assertFalse(ty.isDeliverable(90, 360));
    assertTrue(TreasuryFutureContract.UB.isDeliverable(340, 360));
    assertEquals(200_000, TreasuryFutureContract.TU.face(), 0.0);
    assertEquals(1, TreasuryFutureContract.FV.roundingMonths());
    assertEquals("10-Year T-Note", ty.description());
    assertThrows(IllegalArgumentException.class, () -> BasisAnalytics.cheapestToDeliver(
        List.of()));
  }

  /**
   * Deliverable windows read from the exchange's rule text on 2026-11-23 (review A11). The review
   * recalled a 10-year cap for TY; the rule says less than 8 years, and "less than" was the part
   * the enum had wrong (it admitted exactly 8 years).
   */
  @Test
  void deliverableWindowsFollowTheCbotRulebook() {
    // TY, CBOT Rule 19101.A as amended by CBOT Submission 25-099 (contract months from March
    // 2026; the window is the same for earlier months): original term not more than 10 years,
    // remaining term "not less than 6 years 6 months and less than 8 years"
    final TreasuryFutureContract ty = TreasuryFutureContract.TY;
    assertFalse(ty.isDeliverable(77, 120));
    assertTrue(ty.isDeliverable(78, 120));
    assertTrue(ty.isDeliverable(95, 120));
    assertFalse(ty.isDeliverable(96, 120), "exactly 8 years is not less than 8 years");
    assertTrue(ty.isDeliverable(80, 84), "an original 7-year note");
    assertFalse(ty.isDeliverable(90, 121));
    // TN, CBOT Rule 26101.A (same submission): original not more than 10 years, remaining not
    // less than 9 years 5 months
    final TreasuryFutureContract tn = TreasuryFutureContract.TN;
    assertFalse(tn.isDeliverable(112, 120));
    assertTrue(tn.isDeliverable(113, 120));
    assertTrue(tn.isDeliverable(120, 120));
    // Z3N, CBOT Rule 39101.A as amended by CBOT Submission 20-119 (effective 2020-07-13):
    // original not more than 7 years, remaining not less than 2 years 9 months and not more than
    // 3 years
    final TreasuryFutureContract z3n = TreasuryFutureContract.Z3N;
    assertFalse(z3n.isDeliverable(32, 84));
    assertTrue(z3n.isDeliverable(33, 84));
    assertTrue(z3n.isDeliverable(36, 84));
    assertFalse(z3n.isDeliverable(37, 84));
    assertFalse(z3n.isDeliverable(34, 85));
    // T-Bond: at least 15 years and less than 25 (the cap CBOT added from the March 2011 month)
    assertTrue(TreasuryFutureContract.US.isDeliverable(299, 360));
    assertFalse(TreasuryFutureContract.US.isDeliverable(300, 360));
    assertTrue(TreasuryFutureContract.UB.isDeliverable(300, 360));
  }

  @Test
  void futuresDv01IsCtdDv01OverConversionFactor() {
    final BondPricer p = new BondPricer();
    final FixedRateBond ctd = new FixedRateBond("ctd", BondConvention.UST, 0.04125,
        Dates.of(2022, 11, 15), Dates.of(2032, 11, 15));
    final int settle = Dates.of(2025, 1, 16);
    final double cf = ConversionFactors.cme(0.04125, ctd.maturity(), 2025, 3,
        TreasuryFutureContract.TY);
    final BasisAnalytics a = new BasisAnalytics();
    final double dv = a.futuresDv01(ctd, 0.045, settle, cf, 100_000);
    assertEquals(p.dv01(ctd, 0.045, settle, false) / cf * 1000.0, dv, 1e-9);
    final double eurex = ConversionFactors.eurex(new FixedRateBond("b", BondConvention.BUND,
        0.06, Dates.of(2024, 2, 15), Dates.of(2034, 2, 15)), Dates.of(2025, 2, 15), p);
    assertEquals(1.0, eurex, 1e-6);
  }
}
