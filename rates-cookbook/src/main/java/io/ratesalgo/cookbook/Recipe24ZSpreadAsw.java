package io.ratesalgo.cookbook;

import io.ratesalgo.analytics.bond.BondPricer;
import io.ratesalgo.analytics.bond.FixedRateBond;
import io.ratesalgo.analytics.curve.DiscountCurve;
import io.ratesalgo.analytics.date.DayCount;
import java.util.Locale;

/** COOKBOOK.md recipe 24: z-spread and par-par asset-swap spread of Treasuries to the OIS curve. */
public final class Recipe24ZSpreadAsw {

  private Recipe24ZSpreadAsw() {}

  public static void main(final String[] args) {
    // the SOFR OIS curve of recipe 4 and the Treasury basket of recipe 23, both on 2025-01-15
    final DiscountCurve ois = Recipe04SofrCurve.build().curve();
    final BondPricer pricer = new BondPricer();
    System.out.println("cusip      yield%   dirty      on OIS     z-spread bp  ASW bp");
    for (final Object[] row : Recipe23NssRichCheap.BASKET) {
      final FixedRateBond b = Recipe23NssRichCheap.bond(row);
      final int settle = b.settlementDate(Recipe23NssRichCheap.TODAY);
      final double y = (double) row[4];
      final double dirty = pricer.dirtyPrice(b, y / 100, settle, false);
      final double onCurve = pricer.dirtyFromCurve(b, ois, settle);
      final double z = pricer.zSpread(b, ois, dirty, settle);
      final double asw = pricer.assetSwapSpread(b, ois, dirty, settle, DayCount.ACT_360);
      System.out.println(String.format(Locale.ROOT, "%s  %6.3f  %9.5f  %9.5f  %9.2f  %8.2f",
          b.name(), y, dirty, onCurve, 1e4 * z, 1e4 * asw));
    }
    // a check: priced at its own OIS value, a bond has zero z-spread and zero asset-swap spread
    final FixedRateBond tens = Recipe23NssRichCheap.bond(Recipe23NssRichCheap.BASKET[7]);
    final int settle = tens.settlementDate(Recipe23NssRichCheap.TODAY);
    final double fair = pricer.dirtyFromCurve(tens, ois, settle);
    final double z = pricer.zSpread(tens, ois, fair, settle);
    final double asw = pricer.assetSwapSpread(tens, ois, fair, settle, DayCount.ACT_360);
    System.out.println(tens.name() + " at its OIS value: |z| and |ASW| below 1e-10 bp: "
        + (Math.abs(1e4 * z) < 1e-10 && Math.abs(1e4 * asw) < 1e-10));
  }
}
