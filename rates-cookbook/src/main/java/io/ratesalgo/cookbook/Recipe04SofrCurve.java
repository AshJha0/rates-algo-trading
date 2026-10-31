package io.ratesalgo.cookbook;

import io.ratesalgo.analytics.curve.CurveBuilder;
import io.ratesalgo.analytics.curve.CurveInstrument;
import io.ratesalgo.analytics.curve.DiscountCurve;
import io.ratesalgo.analytics.curve.HullWhite;
import io.ratesalgo.analytics.curve.Interpolation;
import io.ratesalgo.analytics.curve.OisFutureInstrument;
import io.ratesalgo.analytics.curve.OisSwapInstrument;
import io.ratesalgo.analytics.curve.PeriodRateInstrument;
import io.ratesalgo.analytics.date.Calendars;
import io.ratesalgo.analytics.date.Dates;
import io.ratesalgo.analytics.date.DayCount;
import io.ratesalgo.analytics.date.ImmDates;
import io.ratesalgo.analytics.swap.FixingHistory;
import io.ratesalgo.analytics.swap.SwapConvention;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** COOKBOOK.md recipe 4: bootstrap a USD SOFR discount curve from OIS quotes. */
public final class Recipe04SofrCurve {

  private Recipe04SofrCurve() {}

  /** The recipe's curve, shared with recipe 5. */
  public static CurveBuilder build() {
    final int today = Dates.of(2025, 1, 15);
    final List<CurveInstrument> inst = new ArrayList<>();
    // today's SOFR is published tomorrow: the overnight quote is an estimate
    inst.add(new PeriodRateInstrument("SOFR ON", "ON", today,
        Calendars.US_SOFR.advanceBusinessDays(today, 1), DayCount.ACT_360, 0.0430));
    // meeting-dated OIS between FOMC effective dates: a step function in the overnight forward
    final int[] meetings = {Dates.of(2025, 1, 30), Dates.of(2025, 3, 20), Dates.of(2025, 5, 8)};
    final double[] meetingRates = {0.0430, 0.0429, 0.0422};
    int prev = today;
    for (int i = 0; i < meetings.length; i++) {
      inst.add(new PeriodRateInstrument("FOMC " + Dates.format(meetings[i]), "M" + (i + 1), prev,
          meetings[i], DayCount.ACT_360, meetingRates[i]));
      prev = meetings[i];
    }
    // a 3-month SOFR future with its Hull-White convexity adjustment
    final int start = ImmDates.thirdWednesday(2025, 9);
    final int end = ImmDates.thirdWednesday(2025, 12);
    final double ca = HullWhite.futuresConvexity(HullWhite.DEFAULT_A, HullWhite.DEFAULT_SIGMA,
        (start - today) / 365.0, (end - today) / 365.0);
    inst.add(OisFutureInstrument.threeMonth("SR3U5", 2025, 9, Calendars.US_SOFR, DayCount.ACT_360,
        ca, 96.05));
    // par OIS swaps
    final int[] months = {24, 60, 120, 360};
    final double[] rates = {0.0380, 0.0383, 0.0399, 0.0397};
    for (int i = 0; i < months.length; i++) {
      inst.add(new OisSwapInstrument(SwapConvention.USD_SOFR, today, months[i],
          (months[i] / 12) + "Y", rates[i]));
    }
    final CurveBuilder b = new CurveBuilder(today, inst, Interpolation.LINEAR_LOG_DF, 0.0, null,
        FixingHistory.EMPTY);
    b.build(CurveBuilder.Method.AUTO);
    return b;
  }

  public static void main(final String[] args) {
    final CurveBuilder b = build();
    final DiscountCurve curve = b.curve();
    System.out.println("method " + b.resolve(CurveBuilder.Method.AUTO));
    for (int i = 0; i < curve.pillarCount(); i++) {
      final int d = curve.pillarDate(i);
      System.out.println(String.format(Locale.ROOT, "%-6s %s  df=%.10f  zero=%.6f%%",
          b.instruments().get(i).tenor(), Dates.format(d), curve.df(d), 100 * curve.zeroRate(d)));
    }
    System.out.println("every instrument reprices within 1e-12: "
        + (b.maxRepricingError() <= CurveBuilder.SOLVER_TOLERANCE));
  }
}
