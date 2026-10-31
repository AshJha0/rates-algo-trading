package io.ratesalgo.cookbook;

import io.ratesalgo.analytics.bond.BondConvention;
import io.ratesalgo.analytics.bond.BondPricer;
import io.ratesalgo.analytics.bond.FixedRateBond;
import io.ratesalgo.analytics.date.Dates;
import java.util.Locale;

/** COOKBOOK.md recipe 7: a gilt's accrued interest inside the ex-dividend period. */
public final class Recipe07GiltExDividend {

  private Recipe07GiltExDividend() {}

  public static void main(final String[] args) {
    final BondPricer p = new BondPricer();
    // 4 1/8% Treasury Gilt 2027, coupons 22 January and 22 July
    final FixedRateBond gilt = new FixedRateBond("UKT 4 1/8 2027", BondConvention.GILT, 0.04125,
        Dates.of(2022, 7, 22), Dates.of(2027, 1, 22));
    final int coupon = Dates.of(2025, 1, 22);
    int k = 0;
    while (gilt.couponDate(k) != coupon) {
      k++;
    }
    System.out.println("ex-dividend date " + Dates.format(gilt.exDividendDate(k)));
    for (final int trade : new int[] {Dates.of(2025, 1, 9), Dates.of(2025, 1, 10),
        Dates.of(2025, 1, 21)}) {
      final int settle = gilt.settlementDate(trade);
      System.out.println(String.format(Locale.ROOT, "settle %s  accrued %+.6f  clean at 4.36%% %.6f",
          Dates.format(settle), p.accrued(gilt, settle), p.cleanPrice(gilt, 0.0436, settle)));
    }
  }
}
