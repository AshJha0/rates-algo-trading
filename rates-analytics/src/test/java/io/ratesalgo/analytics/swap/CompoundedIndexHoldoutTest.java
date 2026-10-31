package io.ratesalgo.analytics.swap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.analytics.curve.DiscountCurve;
import io.ratesalgo.analytics.curve.Interpolation;
import io.ratesalgo.analytics.date.BusinessDayConvention;
import io.ratesalgo.analytics.date.Dates;
import io.ratesalgo.analytics.date.HolidayCalendar;
import io.ratesalgo.analytics.golden.Golden;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Holdout year 2024: the compounded leg of a one-year OIS, built from this module's fixing
 * calendar and day weights, against the compounded indices the central banks publish (NY Fed
 * SOFR Index, ECB compounded EUR STR index, BoE SONIA Compounded Index; data/fixtures, see their
 * provenance files). No parameter was fitted to these data.
 *
 * <p>Checks: (1) the swap's daily observation dates are exactly the publication dates in the
 * fixture, so the fixing calendar is right for every day of the year; (2) there is an unrounded
 * starting index within the published value's rounding interval from which compounding the
 * published rates with the swap's weights reproduces every published index value to the last
 * (eighth) decimal; (3) the swap pricer's compound factor for the year equals the ratio of the
 * published indices to within the indices' own rounding, 2e-8 relative.
 */
class CompoundedIndexHoldoutTest {

  record Row(int date, double rate, double index) {}

  static List<Row> load(final String file) throws IOException {
    final List<String> lines =
        Files.readAllLines(Golden.repoRoot().resolve(Path.of("data", "fixtures", file)),
            StandardCharsets.UTF_8);
    final List<Row> rows = new ArrayList<>();
    for (final String line : lines.subList(1, lines.size())) {
      final String[] f = line.split(",", -1);
      if (!f[2].isEmpty()) {
        rows.add(new Row(Dates.parse(f[0]), Double.parseDouble(f[1]) / 100.0,
            Double.parseDouble(f[2])));
      }
    }
    return rows;
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({"USD, sofr_2024.csv", "EUR, estr_2024.csv", "GBP, sonia_2024.csv"})
  void compoundedLegMatchesPublishedIndexToTheLastDigit(final String ccy, final String file)
      throws IOException {
    final SwapConvention conv = SwapConvention.forCurrency(ccy);
    final List<Row> rows = load(file);
    final int start = Dates.of(2024, 1, 2);
    final int end = Dates.of(2025, 1, 2);
    final OisSwap swap = OisSwap.of(conv, start, end, 1.0, 0.0, true);
    assertEquals(1, swap.floatPeriodCount());
    final int[] fixingDates = swap.fixingDates(0);
    final double[] weights = swap.compoundingWeights(0);
    final List<Row> year = rows.stream().filter(r -> r.date() >= start && r.date() < end).toList();
    assertEquals(year.size(), fixingDates.length, ccy + " publication days in 2024");
    for (int i = 0; i < fixingDates.length; i++) {
      assertEquals(Dates.format(year.get(i).date()), Dates.format(fixingDates[i]), ccy + " " + i);
    }
    final Row first = rows.stream().filter(r -> r.date() == start).findFirst().orElseThrow();
    final Row last = rows.stream().filter(r -> r.date() == end).findFirst().orElseThrow();

    // (2) exact reproduction from some start in the rounding interval of the published start
    int exactStarts = 0;
    for (int k = -500; k <= 500; k++) {
      double x = first.index() + k * 1e-11;
      boolean ok = true;
      for (int i = 0; i < fixingDates.length && ok; i++) {
        x *= 1.0 + year.get(i).rate() * weights[i];
        final double published = i + 1 < year.size() ? year.get(i + 1).index() : last.index();
        ok = Math.round(x * 1e8) == Math.round(published * 1e8);
      }
      if (ok) {
        exactStarts++;
      }
    }
    System.out.println("HOLDOUT " + ccy + " 2024 starts reproducing every published index: "
        + exactStarts + " of 1001");
    assertTrue(exactStarts > 0, ccy + ": no start reproduces the published index to 8 decimals");

    // (3) the pricer's compound factor against the published ratio
    final int[] pillar = {end + 3650};
    final DiscountCurve flat =
        DiscountCurve.fromDiscountFactors(start - 1, pillar, new double[] {1.0},
            Interpolation.LINEAR_LOG_DF, 0.0);
    final int[] dates = new int[year.size()];
    final double[] rates = new double[year.size()];
    for (int i = 0; i < dates.length; i++) {
      dates[i] = year.get(i).date();
      rates[i] = year.get(i).rate();
    }
    final OisSwapPricer pricer = new OisSwapPricer();
    final int today = fixingDates[fixingDates.length - 1];
    final double c = 1.0 + pricer.floatLegPv(swap, flat, flat, new FixingHistory(dates, rates),
        today);
    final double ratio = last.index() / first.index();
    final double rel = Math.abs(c / ratio - 1.0);
    System.out.println("HOLDOUT " + ccy + " 2024 |compound factor / index ratio - 1| = " + rel);
    assertTrue(rel <= 2e-8, ccy + " compound factor " + c + " vs index ratio " + ratio);
  }

  /**
   * Every day of a holdout year on which the fixing calendar and the payment calendar disagree:
   * the swap never observes a fixing on a fixing holiday, the day's accrual is carried by the
   * previous fixing, and payment dates are counted on the payment calendar.
   */
  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({"USD, 2023", "USD, 2024", "USD, 2021", "EUR, 2024", "GBP, 2024"})
  void fixingAndPaymentCalendarsAreSeparateOnEveryMismatchDay(final String ccy, final int year) {
    final SwapConvention conv = SwapConvention.forCurrency(ccy);
    final HolidayCalendar fix = conv.fixingCalendar();
    final HolidayCalendar pay = conv.paymentCalendar();
    int mismatches = 0;
    for (int d = Dates.of(year, 1, 1); d <= Dates.of(year, 12, 31); d++) {
      if (fix.isBusinessDay(d) == pay.isBusinessDay(d)) {
        continue;
      }
      mismatches++;
      // only "payment open, fixing closed" exists for these conventions
      assertTrue(pay.isBusinessDay(d) && !fix.isBusinessDay(d), Dates.format(d));
      // a period that starts on the day
      final OisSwap startsOn = OisSwap.of(conv, d, Dates.addMonths(d, 1), 1.0, 0.0, true);
      final int[] fx = startsOn.fixingDates(0);
      final double[] w = startsOn.compoundingWeights(0);
      assertEquals(fix.adjust(d, BusinessDayConvention.PRECEDING), fx[0], "first observation");
      assertEquals(conv.floatDayCount().yearFraction(d, fix.advanceBusinessDays(d, 1)), w[0],
          1e-15, "first weight runs from the interest start");
      // a period that ends on the day
      final OisSwap endsOn = OisSwap.of(conv, Dates.addMonths(d, -1), d, 1.0, 0.0, true);
      final int[] fe = endsOn.fixingDates(0);
      for (final int f : fe) {
        assertTrue(fix.isBusinessDay(f), "observation on a fixing holiday " + Dates.format(f));
        assertFalse(f == d, "observed the fixing holiday itself");
      }
      final int expectedPay = pay.advanceBusinessDays(endsOn.endDate(), conv.paymentLag(),
          BusinessDayConvention.FOLLOWING);
      assertEquals(expectedPay, endsOn.floatPeriod(0)[2], "payment counted on payment calendar");
      assertTrue(pay.isBusinessDay(endsOn.floatPeriod(0)[2]));
    }
    System.out.println("MISMATCH " + ccy + " " + year + " fixing-versus-payment days: "
        + mismatches);
  }
}
