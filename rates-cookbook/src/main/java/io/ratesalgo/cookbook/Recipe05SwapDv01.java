package io.ratesalgo.cookbook;

import io.ratesalgo.analytics.curve.CurveBuilder;
import io.ratesalgo.analytics.curve.DiscountCurve;
import io.ratesalgo.analytics.risk.Dv01Ladder;
import io.ratesalgo.analytics.risk.KeyRateRisk;
import io.ratesalgo.analytics.swap.FixingHistory;
import io.ratesalgo.analytics.swap.OisSwap;
import io.ratesalgo.analytics.swap.OisSwapPricer;
import io.ratesalgo.analytics.swap.SwapConvention;
import java.util.Locale;

/** COOKBOOK.md recipe 5: price a 10-year SOFR swap, its par rate and its DV01 ladder. */
public final class Recipe05SwapDv01 {

  private Recipe05SwapDv01() {}

  public static void main(final String[] args) {
    final CurveBuilder b = Recipe04SofrCurve.build();
    final DiscountCurve curve = b.curve();
    final int today = curve.valuationDate();
    // pay fixed 4% on 100 million for 10 years from spot
    final OisSwap swap = OisSwap.spot(SwapConvention.USD_SOFR, today, 120, 1e8, 0.04, true);
    final OisSwapPricer pricer = new OisSwapPricer();
    final FixingHistory none = FixingHistory.EMPTY;
    System.out.println(String.format(Locale.ROOT, "par rate %.6f%%",
        100 * pricer.parRate(swap, curve, none, today)));
    System.out.println(String.format(Locale.ROOT, "pv %.2f USD", pricer.pv(swap, curve, none,
        today)));
    System.out.println(String.format(Locale.ROOT, "annuity %.6f",
        pricer.annuity(swap, curve, today)));

    // key-rate DV01 by algorithmic differentiation through the bootstrap
    final double[] g = new double[curve.pillarCount()];
    pricer.pvGradient(swap, curve, curve, none, today, g, g);
    final Dv01Ladder ladder = KeyRateRisk.fromGradient(b, "USD", g);
    for (int i = 0; i < ladder.size(); i++) {
      System.out.println(String.format(Locale.ROOT, "  %-5s %12.2f", ladder.tenors().get(i),
          Math.round(ladder.dv01(i) * 100) / 100.0 + 0.0));
    }
    System.out.println(String.format(Locale.ROOT, "parallel DV01 %.2f USD per bp",
        ladder.parallel()));
    // the same by bumping each quote 1bp up and down and rebuilding
    final Dv01Ladder bumped = KeyRateRisk.byBumping(b, "USD",
        () -> pricer.pv(swap, curve, none, today), KeyRateRisk.BP);
    System.out.println(String.format(Locale.ROOT, "bumped parallel DV01 %.2f USD per bp",
        bumped.parallel()));
  }
}
