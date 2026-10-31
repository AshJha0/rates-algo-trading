package io.ratesalgo.cookbook;

import io.ratesalgo.analytics.bond.BondConvention;
import io.ratesalgo.analytics.bond.BondPricer;
import io.ratesalgo.analytics.bond.FixedRateBond;
import io.ratesalgo.analytics.date.Dates;
import java.util.Locale;

/** COOKBOOK.md recipe 6: price a Treasury from yield, with accrued interest, duration and DV01. */
public final class Recipe06Treasury {

  private Recipe06Treasury() {}

  public static void main(final String[] args) {
    final BondPricer p = new BondPricer();
    // the 4 1/4% note of 15 November 2034 (CUSIP 91282CLW9)
    final FixedRateBond note = new FixedRateBond("91282CLW9", BondConvention.UST, 0.0425,
        Dates.of(2024, 11, 15), Dates.of(2034, 11, 15));
    final int settle = note.settlementDate(Dates.of(2025, 1, 15));
    final double y = 0.0466;
    System.out.println("settlement " + Dates.format(settle));
    System.out.println(String.format(Locale.ROOT, "clean %.6f  accrued %.6f  dirty %.6f",
        p.cleanPrice(note, y, settle), p.accrued(note, settle),
        p.dirtyPrice(note, y, settle, false)));
    System.out.println(String.format(Locale.ROOT, "modified duration %.6f  convexity %.4f",
        p.modifiedDuration(note, y, settle, false), p.convexity(note, y, settle, false)));
    System.out.println(String.format(Locale.ROOT, "DV01 per 1mm face %.2f USD",
        p.dv01(note, y, settle, false) * 1e4));
    final double clean = p.cleanPrice(note, y, settle);
    System.out.println(String.format(Locale.ROOT, "street yield %.8f%%  true yield %.8f%%",
        100 * p.yieldFromClean(note, clean, settle, false),
        100 * p.yieldFromClean(note, clean, settle, true)));
    // the reopening auction of 11 December 2024: high yield 4.235%, published price 100.114150
    final int issue = Dates.of(2024, 12, 16);
    final long dirty6 = Math.round(p.treasuryAuctionDirtyPrice(note, 0.04235, issue) * 1e6);
    final long accrued6 = Math.round(p.accrued(note, issue) * 1e6);
    System.out.println(String.format(Locale.ROOT, "auction price %.6f (published 100.114150)",
        (dirty6 - accrued6) / 1e6));
  }
}
