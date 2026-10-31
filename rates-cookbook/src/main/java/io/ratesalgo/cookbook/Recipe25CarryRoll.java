package io.ratesalgo.cookbook;

import io.ratesalgo.analytics.bond.BondCarry;
import io.ratesalgo.analytics.bond.BondPricer;
import io.ratesalgo.analytics.bond.FixedRateBond;
import io.ratesalgo.analytics.bond.NelsonSiegelSvensson;
import io.ratesalgo.analytics.date.BusinessDayConvention;
import io.ratesalgo.analytics.date.Dates;
import io.ratesalgo.analytics.funding.RepoCurve;
import java.util.Locale;

/** COOKBOOK.md recipe 25: carry, roll-down and the repo-implied forward price of a Treasury. */
public final class Recipe25CarryRoll {

  private Recipe25CarryRoll() {}

  public static void main(final String[] args) {
    // the 4 1/4% note of November 2034 at 4.66%, the 10-year line of recipe 23's basket
    final FixedRateBond note = Recipe23NssRichCheap.bond(Recipe23NssRichCheap.BASKET[7]);
    final int settle = note.settlementDate(Recipe23NssRichCheap.TODAY);
    final double y = 0.0466;
    final BondPricer pricer = new BondPricer();
    final double clean = pricer.cleanPrice(note, y, settle);
    // overnight point: TGCR of 2025-01-14 (4.27%, data/fixtures/repo_gc_2024.csv); term points declared
    final RepoCurve gc = new RepoCurve(new int[] {1, 30, 90, 180},
        new double[] {0.0427, 0.0425, 0.0421, 0.0415});
    final NelsonSiegelSvensson nss = Recipe23NssRichCheap.fit(new double[1]);
    final BondCarry carry = new BondCarry();
    System.out.println(String.format(Locale.ROOT, "%s settle %s clean %.6f yield %.3f%%",
        note.name(), Dates.format(settle), clean, 100 * y));
    System.out.println("horizon     days  repo%   fwd clean   carry    roll    total   be yield%  be chg bp");
    for (final int months : new int[] {1, 3, 6}) {
      final int horizon = note.convention().calendar().adjust(Dates.addMonths(settle, months),
          BusinessDayConvention.FOLLOWING);
      final int days = horizon - settle;
      final double repo = gc.rate(days);
      final double fwd = carry.forwardCleanPrice(note, clean, settle, horizon, repo);
      final double c = carry.carry(note, clean, settle, horizon, repo);
      final double today = nss.zero((note.maturity() - settle) / 365.25) / 100;
      final double later = nss.zero((note.maturity() - horizon) / 365.25) / 100;
      final double roll = carry.rollDown(note, y, horizon, today, later);
      final double breakeven = pricer.yieldFromClean(note, fwd, horizon, false);
      System.out.println(String.format(Locale.ROOT,
          "%s  %4d  %5.3f  %10.6f  %+7.4f  %+7.4f  %+7.4f  %8.4f  %+8.2f",
          Dates.format(horizon), days, 100 * repo, fwd, c, roll, c + roll, 100 * breakeven,
          1e4 * (breakeven - y)));
    }
    // trading special: 50 bp below GC for three months raises the carry by the funding saved
    final int horizon = Dates.addMonths(settle, 3);
    final double gcCarry = carry.carry(note, clean, settle, horizon, gc.rate(horizon - settle));
    final double special = clean - carry.forwardCleanPrice(note, clean, settle, horizon, gc, 0.0050);
    System.out.println(String.format(Locale.ROOT,
        "3m carry at GC %+.4f, 50 bp special %+.4f, difference %.4f per 100", gcCarry, special,
        special - gcCarry));
    System.out.println(String.format(Locale.ROOT, "funding saved on the dirty price %.4f per 100",
        RepoCurve.fundingCharge(clean + pricer.accrued(note, settle), horizon - settle, 0.0050)));
  }
}
