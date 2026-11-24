package io.ratesalgo.cookbook;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Instrument;
import io.ratesalgo.quoting.CoverModel;
import io.ratesalgo.quoting.TierPriors;
import io.ratesalgo.sim.Competition;
import io.ratesalgo.sim.MarketSpec;
import io.ratesalgo.sim.SimInstrument;
import io.ratesalgo.sim.SimParams;
import io.ratesalgo.sim.SimParams.Scalar;
import java.util.Locale;

/** COOKBOOK.md recipe 42: the break-even half-spread and the cover-relative quote above it. */
public final class Recipe42BreakEven {

  private Recipe42BreakEven() {}

  public static void main(final String[] args) {
    final SimParams p = SimParams.loadDefault();
    final double median = p.get(Scalar.MEDIAN_HALF_SPREAD_BP);
    final double bestPerMedian = Competition.expectedBestPerMedian(p);
    final double winning = median * bestPerMedian;
    System.out.printf(
        Locale.ROOT, "expected winning half-spread %.4f x %.3f = %.3f bp%n", median, bestPerMedian, winning);
    System.out.printf(
        Locale.ROOT, "zero-profit identity: adverse move %.3f + all-in cost %.3f = %.3f bp%n",
        p.get(Scalar.EXPECTED_ADVERSE_MOVE_BP), p.get(Scalar.ALL_IN_COST_BP),
        p.get(Scalar.EXPECTED_ADVERSE_MOVE_BP) + p.get(Scalar.ALL_IN_COST_BP));

    // hedge cost: crossing a book one tick wide costs half a tick of the 10-year note
    final MarketSpec spec = new MarketSpec(p);
    final SimInstrument note = spec.instrument(spec.indexOf("UST-10Y"));
    final double hedge = 0.5 * Math.abs(note.toBp(note.tick()));
    System.out.printf(Locale.ROOT, "half a tick of the 10-year note = %.3f bp%n", hedge);

    // the average client, then tiers A, B and C; no inventory
    final TierPriors priors = TierPriors.calibrated();
    final String[] names = {"average", "tier A", "tier B", "tier C"};
    final double[] adverse = {
      p.get(Scalar.EXPECTED_ADVERSE_MOVE_BP),
      priors.adverseBp(TierPriors.TIER_A),
      priors.adverseBp(TierPriors.TIER_B),
      priors.adverseBp(TierPriors.TIER_C)
    };

    // a 10-year swap of typical size shown to four dealers
    final Instrument swap =
        Instrument.swap(1, "SOFR-10Y", CurrencyCode.USD, 10.0, 8.11, 0, 0.0, 2_500_000_000L);
    final int cell = CoverModel.cell(swap, 1.0, 4);
    final CoverModel cover = CoverModel.calibrated();
    final double fill = priors.fillProbability(TierPriors.TIER_A);
    System.out.printf(Locale.ROOT, "predicted cover median %.3f bp%n", cover.predictedCoverBp(cell));
    System.out.println("client   adverse  break-even  winning less break-even  quote  P(win)");
    for (int k = 0; k < names.length; k++) {
      final double breakEven = adverse[k] + hedge;
      final double quote = cover.optimal(cell, fill, breakEven);
      System.out.printf(
          Locale.ROOT, "%-8s %.3f    %.3f       %+.3f                   %.3f  %.2f%n",
          names[k], adverse[k], breakEven, winning - breakEven, quote, cover.winProbability(cell, fill, quote));
    }

    // outcomes teach the cell: a win with its cover shown, a loss and a win without one
    cover.observeExact(cell, 0.100);
    cover.observeBelow(cell, 0.150);
    cover.observeAbove(cell, 0.110);
    System.out.printf(
        Locale.ROOT, "after %d outcomes: cover median %.3f bp, quote to the average client %.3f bp%n",
        cover.observations(cell), cover.predictedCoverBp(cell), cover.optimal(cell, fill, adverse[0] + hedge));
  }
}
