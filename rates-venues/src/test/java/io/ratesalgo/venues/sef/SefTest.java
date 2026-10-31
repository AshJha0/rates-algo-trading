package io.ratesalgo.venues.sef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.venues.Venues;
import io.ratesalgo.venues.feed.InProcessFeedChannel;
import io.ratesalgo.venues.sim.VenueHarness;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SefTest {

  @Test
  void limitHubReservesAndReleases() {
    final FcmLimitHub hub = new FcmLimitHub(Map.of("F1", 100L), Map.of("P", "F1", "Q", "F9"));
    assertNull(hub.reserve("P", 60));
    assertTrue(hub.reserve("P", 50).contains("limit"));
    hub.release("P", 60);
    assertEquals(0, hub.used("F1"));
    assertTrue(hub.reserve("Q", 1).contains("no FCM"));
    assertTrue(hub.reserve("Z", 1).contains("no FCM"));
    assertEquals(3, hub.rejections());
    hub.release("Z", 5);
  }

  @Test
  void rfqTo3RuleAndOutcomesWithoutEngine() {
    final ManualClock clock = new ManualClock(Venues.T0);
    final SefSimulator sef = (SefSimulator) Venues.create("sef", clock, 1, new InProcessFeedChannel());
    try (VenueHarness h = new VenueHarness(sef, clock)) {
      assertTrue(sef.requiresRfqTo3("USD-SOFR-10Y", 50_000_000));
      assertFalse(sef.requiresRfqTo3("USD-SOFR-10Y", 170_000_000));
      assertFalse(sef.requiresRfqTo3("USD-SOFR-12Y", 1_000_000));
      assertEquals(-1, sef.injectRfq(new SefRfqSpec("USD-SOFR-2Y", true, 1_000_000, 2, 2001, List.of(), true)));
      final long pass = sef.injectRfq(new SefRfqSpec("USD-SOFR-2Y", true, 1_000_000, 3, 2001, List.of(3_600_000_000L), false));
      sef.decide(pass);
      final long credit = sef.injectRfq(new SefRfqSpec("USD-SOFR-30Y", false, 400_000_000, 3, 2002, List.of(3_750_000_000L), true));
      sef.decide(credit);
      final long ok = sef.injectRfq(new SefRfqSpec("USD-SOFR-5Y", false, 10_000_000, 3, 2001, List.of(3_650_000_000L, 3_651_000_000L), true));
      h.advance(6_000_000_000L);
      sef.decide(ok);
      sef.decide(999);
      final Map<SefSimulator.Outcome, Integer> outcomes = sef.outcomes();
      assertEquals(1, outcomes.get(SefSimulator.Outcome.REFUSED));
      assertEquals(1, outcomes.get(SefSimulator.Outcome.PASSED));
      assertEquals(1, outcomes.get(SefSimulator.Outcome.CREDIT_REJECTED));
      assertEquals(1, outcomes.get(SefSimulator.Outcome.DONE));
      assertEquals(10_000_000, sef.hub().used("FCM-1"));
      assertThrows(IllegalArgumentException.class,
          () -> sef.injectRfq(new SefRfqSpec("NOPE", true, 1, 3, 1, List.of(), true)));
      assertEquals(4_000_000L * 125, SefConfig.defaults().withLimit("FCM-1", 500_000_000L).fcmLimits().get("FCM-1"));
    }
  }
}
