package io.ratesalgo.analytics.swap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.ratesalgo.analytics.date.Dates;
import org.junit.jupiter.api.Test;

/**
 * {@link OisSwap#withFixedRate} must give the same schedule (review 2026-11-23, A9). The 2-year
 * USD SOFR swap traded on 2026-11-02 starts on Wednesday 2026-11-04 and its unadjusted end,
 * 2028-11-04, is a Saturday, paid on Monday 2028-11-06. Rebuilding from the adjusted end rolled
 * the schedule back from the 6th and left a two-day stub from 4 to 6 November 2026.
 */
class OisSwapWithFixedRateTest {

  private static final int TRADE = Dates.of(2026, 11, 2);

  @Test
  void twoYearEndingOnASaturdayKeepsItsScheduleWhenTheRateChanges() {
    final OisSwap swap = OisSwap.spot(SwapConvention.USD_SOFR, TRADE, 24, 1e8, 0.0375, true);
    assertEquals(Dates.of(2026, 11, 4), swap.startDate());
    assertEquals(Dates.SATURDAY, Dates.dayOfWeek(Dates.of(2028, 11, 4)));
    assertEquals(Dates.of(2028, 11, 6), swap.endDate());
    assertEquals(2, swap.fixedPeriodCount());

    final OisSwap repriced = swap.withFixedRate(0.04);
    assertEquals(0.04, repriced.fixedRate(), 0.0);
    assertEquals(swap.notional(), repriced.notional(), 0.0);
    assertEquals(swap.isPayer(), repriced.isPayer());
    assertEquals(swap.startDate(), repriced.startDate());
    assertEquals(swap.endDate(), repriced.endDate());
    assertEquals(2, repriced.fixedPeriodCount(), "no stub period");
    assertEquals(swap.floatPeriodCount(), repriced.floatPeriodCount());
    for (int i = 0; i < swap.fixedPeriodCount(); i++) {
      assertArrayEquals(swap.fixedPeriod(i), repriced.fixedPeriod(i), "fixed period " + i);
    }
    for (int p = 0; p < swap.floatPeriodCount(); p++) {
      assertArrayEquals(swap.floatPeriod(p), repriced.floatPeriod(p), "float period " + p);
      assertArrayEquals(swap.fixingDates(p), repriced.fixingDates(p), "fixings of period " + p);
      // same dates, same day count: equal to the bit
      assertArrayEquals(swap.compoundingWeights(p), repriced.compoundingWeights(p), 0.0);
    }
  }

  @Test
  void everyTemplateTenorSurvivesARateChange() {
    for (final int months : new int[] {24, 60, 120, 144, 360}) {
      final OisSwap swap = OisSwap.spot(SwapConvention.USD_SOFR, TRADE, months, 1.0, 0.04, false);
      final OisSwap again = swap.withFixedRate(0.05).withFixedRate(0.04);
      assertEquals(swap.fixedPeriodCount(), again.fixedPeriodCount(), months + "M");
      for (int i = 0; i < swap.fixedPeriodCount(); i++) {
        assertArrayEquals(swap.fixedPeriod(i), again.fixedPeriod(i), months + "M period " + i);
      }
      assertEquals(swap.lastPaymentDate(), again.lastPaymentDate());
    }
  }
}
