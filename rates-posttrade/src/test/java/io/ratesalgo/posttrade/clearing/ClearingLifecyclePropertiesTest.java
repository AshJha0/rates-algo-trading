package io.ratesalgo.posttrade.clearing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TradeSource;
import io.ratesalgo.posttrade.trade.BookAssignment;
import io.ratesalgo.posttrade.trade.Trade;
import io.ratesalgo.posttrade.trade.TradeKey;
import io.ratesalgo.posttrade.trade.TradeStatus;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

/** The clearing lifecycle under arbitrary event sequences (API_SPEC.md Section 17.6). */
class ClearingLifecyclePropertiesTest {

  private static final TradeKey KEY = new TradeKey(2, TradeSource.Rfq, 71);

  @Property(tries = 1000)
  void terminalStatesAreAbsorbingAndRefusalsChangeNothing(
      @ForAll @Size(max = 12) final List<ClearingEvent> events) {
    final ClearingLifecycle lc = new ClearingLifecycle(KEY);
    long t = 0;
    for (final ClearingEvent e : events) {
      final ClearingState before = lc.state();
      final boolean applied = lc.apply(e, t++);
      if (before.terminal()) {
        assertFalse(applied);
      }
      if (!applied) {
        assertEquals(before, lc.state());
      }
    }
    assertEquals(events.size(), lc.history().size());
  }

  @Property(tries = 1000)
  void clearedOnlyThroughSubmittedThenAccepted(@ForAll @Size(max = 12) final List<ClearingEvent> events) {
    final ClearingLifecycle lc = new ClearingLifecycle(KEY);
    for (final ClearingEvent e : events) {
      lc.apply(e, 0);
    }
    final List<ClearingState> path = new ArrayList<>();
    path.add(ClearingState.NEW);
    for (final ClearingLifecycle.Step s : lc.history()) {
      if (s.applied()) {
        path.add(s.to());
      }
    }
    if (lc.state() == ClearingState.CLEARED) {
      assertEquals(List.of(ClearingState.NEW, ClearingState.SUBMITTED, ClearingState.ACCEPTED,
          ClearingState.CLEARED), path);
    }
    if (lc.state() == ClearingState.REJECTED) {
      assertTrue(path.contains(ClearingState.SUBMITTED));
    }
    // replaying the applied steps through the pure function reaches the same state
    ClearingState s = ClearingState.NEW;
    for (final ClearingLifecycle.Step step : lc.history()) {
      s = ClearingLifecycle.next(s, step.event()).orElse(s);
    }
    assertEquals(lc.state(), s);
  }

  @Test
  void simulatorAcceptsWithinLimitAndRejectsBeyondIt() {
    final List<String> rejected = new ArrayList<>();
    final ClearingHouseSimulator ccp =
        new ClearingHouseSimulator((k, why) -> rejected.add(k.text())).limit(20, 1_000);
    final BookAssignment b = new BookAssignment(2, 20, "ALG-SWP-RFQ-01");
    final Trade t1 = new Trade(new TradeKey(2, TradeSource.Rfq, 1), 0, 1, 2001, Side.Buy, 1, 800, 1, 1, b,
        TradeStatus.LIVE, 1, false);
    final Trade t2 = new Trade(new TradeKey(2, TradeSource.Rfq, 2), 0, 2, 2001, Side.Sell, 1, 300, 2, 2, b,
        TradeStatus.LIVE, 1, false);
    assertEquals(ClearingState.ACCEPTED, ccp.submit(t1, 1));
    assertEquals(ClearingState.REJECTED, ccp.submit(t2, 2));
    assertEquals(ClearingState.REJECTED, ccp.submit(t2, 3));
    assertEquals(List.of("2:R:2"), rejected);
    assertEquals(1, ccp.endOfCycle(4));
    assertEquals(0, ccp.endOfCycle(5));
    assertEquals(List.of(t1), ccp.clearedReport());
    assertEquals(ClearingState.CLEARED, ccp.lifecycle(t1.key()).state());
    assertEquals(null, ccp.lifecycle(new TradeKey(2, TradeSource.Rfq, 3)));
    assertTrue(ClearingState.CLEARED.terminal());
    assertFalse(ClearingState.ACCEPTED.terminal());
  }
}
