package io.ratesalgo.venues.d2c;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.venues.Venues;
import io.ratesalgo.venues.feed.InProcessFeedChannel;
import io.ratesalgo.venues.sim.SimRng;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The D2C models without a FIX session: arrivals, outcomes, markouts, determinism. */
class D2cModelTest {

  static final long HOUR = 3_600_000_000_000L;
  static final long START = 1_793_620_800_000_000_000L; // 06:00 New York

  private static D2cRfqSimulator runDay(final long seed, final double informedFraction) {
    final ManualClock clock = new ManualClock(START);
    final D2cConfig base = D2cConfig.defaults();
    final D2cConfig cfg =
        new D2cConfig(base.responseWindowNanos(), base.minDealers(), base.maxDealers(), base.dealerResponseProbability(),
            base.dealerDelayMedianNanos(), base.dealerDelaySigma(), base.bondHalfSpreadMedian(), base.swapHalfSpreadMedian(),
            base.halfSpreadSigma(), base.toleranceMedian(), base.toleranceSigma(), base.doneAwayProbability(),
            informedFraction, base.informedJumpMultiple(), base.informedLagNanos(), base.markoutHorizonNanos(),
            base.bondVolPerSqrtHour(), base.swapVolPerSqrtHour(), base.zone(), base.basePerHour(), base.events(),
            base.bondSizes(), base.swapSizes());
    final D2cRfqSimulator sim = new D2cRfqSimulator(cfg, Venues.treasuries(), Venues.ENGINES, clock, seed);
    sim.startAutoFlow();
    final long end = clock.nanoTime() + 12 * HOUR;
    while (clock.nanoTime() < end) {
      final long next = Math.min(sim.nextEventNanos(), end);
      clock.advance(Math.max(0, next - clock.nanoTime()));
      sim.onTime();
    }
    return sim;
  }

  @Test
  void sameSeedSameDay() {
    final D2cRfqSimulator a = runDay(11, 0.2);
    final D2cRfqSimulator b = runDay(11, 0.2);
    assertEquals(a.eventLog(), b.eventLog());
    assertEquals(a.trades(), b.trades());
    final D2cRfqSimulator c = runDay(12, 0.2);
    assertTrue(!a.eventLog().equals(c.eventLog()));
  }

  @Test
  void outcomesCoverEveryKindAndArrivalsClusterAroundReleases() {
    final D2cRfqSimulator sim = runDay(5, 0.2);
    final Map<RfqOutcome, Integer> o = sim.outcomes();
    for (final RfqOutcome kind : new RfqOutcome[] {RfqOutcome.DONE, RfqOutcome.DONE_AWAY, RfqOutcome.PASSED}) {
      assertTrue(o.getOrDefault(kind, 0) > 0, kind + " in " + o);
    }
    final D2cConfig cfg = sim.config();
    assertTrue(cfg.intensityPerHour(LocalTime.of(8, 30)) > 5 * cfg.intensityPerHour(LocalTime.of(11, 30)));
    long nearRelease = 0;
    long quietHour = 0;
    for (final String line : sim.eventLog()) {
      if (line.contains(" rfq ") && line.contains(" dealers ")) {
        final long t = Long.parseLong(line.substring(0, line.indexOf(' ')));
        final long minutesNy = ((t / 60_000_000_000L) - 5 * 60) % (24 * 60);
        if (minutesNy >= 8 * 60 + 15 && minutesNy < 8 * 60 + 45) {
          nearRelease++;
        } else if (minutesNy >= 11 * 60 + 15 && minutesNy < 11 * 60 + 45) {
          quietHour++;
        }
      }
    }
    assertTrue(nearRelease > 2 * quietHour, "near 08:30 " + nearRelease + " vs 11:30 " + quietHour);
  }

  @Test
  void informedClientsHaveWorseMarkoutsForTheDealer() {
    final D2cRfqSimulator sim = runDay(21, 0.5);
    long informed = 0;
    long uninformed = 0;
    int ni = 0;
    int nu = 0;
    for (final TradeRecord t : sim.trades()) {
      if (t.midAtHorizon() == 0) {
        continue;
      }
      if (t.informed()) {
        informed += t.dealerMarkout();
        ni++;
      } else {
        uninformed += t.dealerMarkout();
        nu++;
      }
    }
    assertTrue(ni > 20 && nu > 20, ni + " " + nu);
    assertTrue((double) informed / ni < (double) uninformed / nu,
        "mean informed markout " + informed / ni + " vs uninformed " + uninformed / nu);
  }

  @Test
  void configAndPresetRules() {
    assertThrows(IllegalArgumentException.class, () -> D2cConfig.preset("missing"));
    final SimRng rng = new SimRng(1);
    final SizePreset sizes = D2cConfig.defaults().bondSizes();
    assertEquals(5, sizes.buckets());
    for (int i = 0; i < 100; i++) {
      assertTrue(sizes.draw(rng) >= 1_000_000);
      final int b = rng.between(3, 5);
      assertTrue(b >= 3 && b <= 5);
    }
    assertEquals(2, rng.discrete(new double[] {0, 0, 1}));
    assertTrue(rng.exponential(1.0) >= 0);
    final ManualClock clock = new ManualClock(START);
    final D2cRfqSimulator sim = (D2cRfqSimulator) Venues.create("d2c", clock, 1, new InProcessFeedChannel());
    assertThrows(IllegalArgumentException.class,
        () -> sim.injectRfq(new RfqSpec("NOPE", true, 1, 3, List.of(), 1, ClientDecision.BEST, false)));
    assertThrows(IllegalArgumentException.class, () -> Venues.create("x", clock, 1, new InProcessFeedChannel()));
  }
}
