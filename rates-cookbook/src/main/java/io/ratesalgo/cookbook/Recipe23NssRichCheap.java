package io.ratesalgo.cookbook;

import io.ratesalgo.analytics.bond.BondConvention;
import io.ratesalgo.analytics.bond.BondPricer;
import io.ratesalgo.analytics.bond.FixedRateBond;
import io.ratesalgo.analytics.bond.NelsonSiegelSvensson;
import io.ratesalgo.analytics.date.Dates;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

/** COOKBOOK.md recipe 23: fit a Nelson-Siegel-Svensson curve to Treasury yields; rich and cheap. */
public final class Recipe23NssRichCheap {

  private Recipe23NssRichCheap() {}

  static final int TODAY = Dates.of(2025, 1, 15);
  static final double[] TENORS = {1 / 12.0, 2 / 12.0, 0.25, 4 / 12.0, 0.5, 1, 2, 3, 5, 7, 10, 20, 30};

  /** Coupon Treasuries from data/fixtures/ust_auctions_2024.csv with declared street yields. */
  static final Object[][] BASKET = {
      {"91282CME8", 0.04250, "2024-12-31", "2026-12-31", 4.270},
      {"91282CMB4", 0.04000, "2024-12-16", "2027-12-15", 4.335},
      {"91282CMA6", 0.04125, "2024-12-02", "2029-11-30", 4.440},
      {"91282CMD0", 0.04375, "2024-12-31", "2029-12-31", 4.452},
      {"91282CLZ2", 0.04125, "2024-12-02", "2031-11-30", 4.552},
      {"91282CMC2", 0.04500, "2024-12-31", "2031-12-31", 4.561},
      {"91282CLF6", 0.03875, "2024-08-15", "2034-08-15", 4.652},
      {"91282CLW9", 0.04250, "2024-11-15", "2034-11-15", 4.660},
      {"912810UF3", 0.04625, "2024-12-02", "2044-11-15", 4.951},
      {"912810UE6", 0.04500, "2024-11-15", "2054-11-15", 4.882},
  };

  static FixedRateBond bond(final Object[] row) {
    return new FixedRateBond((String) row[0], BondConvention.UST, (double) row[1],
        Dates.parse((String) row[2]), Dates.parse((String) row[3]));
  }

  /** The Treasury par curve of 2025-01-15 (percent) from data/fixtures/ust_par_curve_2015_2025.csv. */
  static double[] parCurve() {
    try {
      final List<String> lines = Files.readAllLines(
          RepoRoot.find().resolve("data/fixtures/ust_par_curve_2015_2025.csv"), StandardCharsets.UTF_8);
      for (final String line : lines) {
        if (line.startsWith(Dates.format(TODAY))) {
          final String[] f = line.split(",", -1);
          final double[] y = new double[TENORS.length];
          for (int i = 0; i < y.length; i++) {
            y[i] = Double.parseDouble(f[i + 1]);
          }
          return y;
        }
      }
      throw new IllegalStateException("no par curve row for " + Dates.format(TODAY));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The fitted curve, shared with recipe 25. */
  static NelsonSiegelSvensson fit(final double[] rms) {
    final double[] start = {4.5, -0.3, -1.0, 1.0, 1.5, 10.0};
    return NelsonSiegelSvensson.fit(TENORS, parCurve(), start, rms);
  }

  public static void main(final String[] args) {
    final double[] rms = new double[1];
    final NelsonSiegelSvensson nss = fit(rms);
    final double[] p = nss.parameters();
    System.out.println(String.format(Locale.ROOT,
        "NSS b0=%.3f b1=%.3f b2=%.3f b3=%.3f t1=%.3f t2=%.3f  rms %.2f bp",
        p[0], p[1], p[2], p[3], p[4], p[5], 100 * rms[0]));

    final BondPricer pricer = new BondPricer();
    System.out.println("cusip      maturity    years  yield%  fit%    resid bp  px vs fit  verdict");
    for (final Object[] row : BASKET) {
      final FixedRateBond b = bond(row);
      final int settle = b.settlementDate(TODAY);
      final double t = (b.maturity() - settle) / 365.25;
      final double y = (double) row[4];
      final double fitted = nss.zero(t);
      final double residBp = 100 * (y - fitted);
      final double dv01 = pricer.dv01(b, y / 100, settle, false);
      final String verdict = Math.abs(residBp) < 0.5 ? "fair" : residBp > 0 ? "cheap" : "rich";
      System.out.println(String.format(Locale.ROOT, "%s  %s  %5.2f  %6.3f  %6.3f  %+7.2f   %+8.4f  %s",
          b.name(), Dates.format(b.maturity()), t, y, fitted, residBp, -residBp * dv01, verdict));
    }
  }
}
