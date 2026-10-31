package io.ratesalgo.posttrade.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TradeSource;
import io.ratesalgo.posttrade.trade.TradeKey;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

/** The settlement lifecycle and the cost of a fail (API_SPEC.md Section 17.7). */
class SettlementProperties {

  private static final TradeKey KEY = new TradeKey(1, TradeSource.Execution, 9);
  private static final LocalDate TRADE_DATE = LocalDate.of(2026, 10, 30);

  @Property(tries = 1000)
  void terminalStatesAbsorbAndNothingSettlesEarly(
      @ForAll @Size(max = 10) final List<SettlementEvent> events,
      @ForAll @Size(min = 10, max = 10) final List<@IntRange(min = -2, max = 5) Integer> offsets) {
    final LocalDate settle = BusinessCalendar.WEEKENDS_ONLY.addBusinessDays(TRADE_DATE, 1);
    final SettlementObligation o = new SettlementObligation(KEY, Side.Sell, settle, 1_000_000);
    for (int i = 0; i < events.size(); i++) {
      final SettlementState before = o.state();
      final LocalDate on = settle.plusDays(offsets.get(i));
      final boolean applied = o.apply(events.get(i), on);
      if (before.terminal()) {
        assertFalse(applied);
      }
      if (applied && events.get(i) != SettlementEvent.CANCEL) {
        assertFalse(on.isBefore(settle));
      }
      if (!applied) {
        assertEquals(before, o.state());
      }
    }
    assertTrue(o.daysFailed(settle.plusDays(30)) >= 0);
    assertEquals(events.size(), o.history().size());
  }

  @Property(tries = 500)
  void failCostIsZeroAtZeroDaysAndGrowsWithDays(
      @ForAll @LongRange(min = 1, max = 10_000_000_000_000L) final long proceeds,
      @ForAll @LongRange(min = 0, max = 10_000_000_000L) final long rate,
      @ForAll @IntRange(min = 0, max = 60) final int days) {
    assertEquals(0, FailCost.fundingMinor(Side.Sell, proceeds, rate, 0).signum());
    final BigDecimal d = FailCost.fundingMinor(Side.Buy, proceeds, rate, days);
    final BigDecimal d1 = FailCost.fundingMinor(Side.Buy, proceeds, rate, days + 1);
    assertTrue(d1.compareTo(d) >= 0);
    assertEquals(d.negate(), FailCost.fundingMinor(Side.Sell, proceeds, rate, days));
    assertTrue(FailCost.failsChargeMinor(Side.Sell, proceeds, rate, days).signum() <= 0);
  }

  @Test
  void workedFailExample() {
    // we sell 10mm face for proceeds of 9,950,000.00 USD and fail for 3 days with funding at 4.30%
    final long proceeds = 995_000_000L;
    assertEquals(0, FailCost.fundingMinor(Side.Sell, proceeds, 4_300_000_000L, 3)
        .compareTo(new BigDecimal("-356541.6666666666666666666666666667")));
    // TMPG charge at a reference rate of 4.0% is zero; at 1.0% it is 2% a year
    assertEquals(0, FailCost.failsChargeMinor(Side.Sell, proceeds, 4_000_000_000L, 3).signum());
    assertEquals(0, FailCost.failsChargeMinor(Side.Buy, proceeds, 1_000_000_000L, 3)
        .compareTo(new BigDecimal("165833.3333333333333333333333333333")));
    assertThrows(IllegalArgumentException.class, () -> FailCost.fundingMinor(Side.Buy, 1, 1, -1));
  }

  @Test
  void failThenCureCountsCalendarDays() {
    final LocalDate settle = BusinessCalendar.WEEKENDS_ONLY.addBusinessDays(TRADE_DATE, 1);
    assertEquals(LocalDate.of(2026, 11, 2), settle);
    final SettlementObligation o = new SettlementObligation(KEY, Side.Buy, settle, 100);
    assertFalse(o.apply(SettlementEvent.SETTLE, TRADE_DATE));
    assertTrue(o.apply(SettlementEvent.FAIL, settle));
    assertTrue(o.apply(SettlementEvent.FAIL, settle.plusDays(1)));
    assertEquals(2, o.daysFailed(settle.plusDays(2)));
    assertTrue(o.apply(SettlementEvent.SETTLE, settle.plusDays(3)));
    assertEquals(3, o.daysFailed(settle.plusDays(10)));
    assertEquals(SettlementState.SETTLED, o.state());
    assertEquals(0, new SettlementObligation(KEY, Side.Buy, settle, 1).daysFailed(settle.plusDays(5)));
    assertEquals(LocalDate.of(2026, 11, 3), BusinessCalendar.WEEKENDS_ONLY.addBusinessDays(TRADE_DATE, 2));
    assertThrows(IllegalArgumentException.class, () -> BusinessCalendar.WEEKENDS_ONLY.addBusinessDays(TRADE_DATE, -1));
    assertThrows(IllegalArgumentException.class, () -> new SettlementObligation(KEY, Side.Buy, settle, 0));
    assertEquals(KEY, o.trade());
    assertEquals(Side.Buy, o.ourSide());
    assertEquals(settle, o.settlementDate());
    assertEquals(100, o.proceedsMinor());
  }
}
