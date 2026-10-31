package io.ratesalgo.cookbook;

import io.ratesalgo.analytics.curve.DiscountCurve;
import io.ratesalgo.analytics.date.Dates;
import io.ratesalgo.analytics.swap.FixingHistory;
import io.ratesalgo.analytics.swap.OisSwap;
import io.ratesalgo.analytics.swap.OisSwapPricer;
import io.ratesalgo.analytics.swap.SwapConvention;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** COOKBOOK.md recipe 26: a seasoned compounded-in-arrears SOFR leg with lookback and lockout. */
public final class Recipe26SofrInArrears {

  private Recipe26SofrInArrears() {}

  /** Rows of data/fixtures/sofr_2024.csv: date, SOFR in percent, SOFR Index. */
  static List<String[]> sofr() {
    try {
      final List<String> lines = Files.readAllLines(
          RepoRoot.find().resolve("data/fixtures/sofr_2024.csv"), StandardCharsets.UTF_8);
      final List<String[]> rows = new ArrayList<>();
      for (final String line : lines.subList(1, lines.size())) {
        rows.add(line.split(",", -1));
      }
      return rows;
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static double index(final List<String[]> rows, final int date) {
    for (final String[] r : rows) {
      if (Dates.parse(r[0]) == date) {
        return Double.parseDouble(r[2]);
      }
    }
    throw new IllegalArgumentException("no SOFR Index on " + Dates.format(date));
  }

  public static void main(final String[] args) {
    final DiscountCurve curve = Recipe04SofrCurve.build().curve();
    final int today = curve.valuationDate();
    // what was published by the morning of 2025-01-15: fixings up to and including 2025-01-14
    final List<String[]> rows = sofr();
    int n = 0;
    while (n < rows.size() && Dates.parse(rows.get(n)[0]) < today) {
      n++;
    }
    final int[] dates = new int[n];
    final double[] values = new double[n];
    for (int i = 0; i < n; i++) {
      dates[i] = Dates.parse(rows.get(i)[0]);
      values[i] = Double.parseDouble(rows.get(i)[1]) / 100.0;
    }
    final FixingHistory fixings = new FixingHistory(dates, values);

    // one quarterly period, 2024-12-16 to 2025-03-17, receiving SOFR on 100 million
    final int start = Dates.of(2024, 12, 16);
    final int end = Dates.of(2025, 3, 17);
    final OisSwapPricer pricer = new OisSwapPricer();
    final SwapConvention base = SwapConvention.USD_SOFR;
    final SwapConvention[] variants = {
        base.withObservation(0, 0, false),
        base.withObservation(2, 0, false),
        base.withObservation(2, 0, true),
        base.withObservation(0, 2, false),
        base.withObservation(5, 2, true),
    };
    System.out.println("convention               days  first obs   last obs    known  rate%     float PV");
    for (final SwapConvention c : variants) {
      final OisSwap leg = OisSwap.of(c, start, end, 1e8, 0.0, false);
      final int[] obs = leg.fixingDates(0);
      int known = 0;
      for (final int d : obs) {
        known += fixings.has(d) ? 1 : 0;
      }
      final double rate = pricer.parRate(leg, curve, fixings, today);
      System.out.println(String.format(Locale.ROOT, "%-23s  %4d  %s  %s  %5d  %7.5f  %11.2f",
          c.name(), obs.length, Dates.format(obs[0]), Dates.format(obs[obs.length - 1]), known,
          100 * rate, pricer.floatLegPv(leg, curve, curve, fixings, today)));
    }

    // the realised part of the plain leg, compounded from fixings, against the SOFR Index ratio
    final OisSwap plain = OisSwap.of(variants[0], start, end, 1e8, 0.0, false);
    final int[] obs = plain.fixingDates(0);
    final double[] w = plain.compoundingWeights(0);
    double growth = 1.0;
    for (int i = 0; i < obs.length && obs[i] < today; i++) {
      growth *= 1.0 + fixings.rate(obs[i]) * w[i];
    }
    final double ratio = index(rows, today) / index(rows, start);
    System.out.println(String.format(Locale.ROOT,
        "realised growth %s to %s: fixings %.8f, SOFR Index ratio %.8f", Dates.format(start),
        Dates.format(today), growth, ratio));
  }
}
