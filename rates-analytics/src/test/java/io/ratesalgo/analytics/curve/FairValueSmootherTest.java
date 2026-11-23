package io.ratesalgo.analytics.curve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The residual rule: how much of a quote's gap to the model reaches the fair value, and when. */
class FairValueSmootherTest {

  private static final long SECOND = 1_000_000_000L;

  @Test
  void theFirstObservationIsTheStartOfDayMark() {
    final FairValueSmoother s = new FairValueSmoother(2, 300.0);
    assertFalse(s.isSet(0));
    assertEquals(0.0, s.residual(0), 0.0);
    assertEquals(0.0372, s.update(0, 0.0372, 0.0370, 10 * SECOND), 0.0);
    assertTrue(s.isSet(0));
    assertEquals(0.0002, s.residual(0), 1e-15);
    assertEquals(0.0375, s.fair(0, 0.0373), 1e-15);
    assertFalse(s.isSet(1));
  }

  @Test
  void oneSkewedUpdateMovesTheFairValueByTheStatedShareOnly() {
    final FairValueSmoother s = new FairValueSmoother(1, 300.0);
    s.reset(0, 0.0370, 0.0370, 0L);
    // half a second later the quote is 1 bp away from a model that has not moved
    final double fair = s.update(0, 0.0371, 0.0370, SECOND / 2);
    final double share = (fair - 0.0370) / 1e-4;
    assertEquals(1.0 - Math.pow(2.0, -0.5 / 300.0), share, 1e-12);
    assertTrue(share < 0.0012, "share " + share);
    assertEquals(share, s.gain(SECOND / 2), 1e-12);
  }

  @Test
  void aGapHeldForOneHalfLifeIsHalfAbsorbedWhateverTheUpdateSpacing() {
    for (final long step : new long[] {SECOND / 2, 7 * SECOND + SECOND / 2, 300 * SECOND}) {
      final FairValueSmoother s = new FairValueSmoother(1, 300.0);
      s.reset(0, 0.0370, 0.0370, 0L);
      double fair = 0.0;
      for (long t = step; t <= 300 * SECOND; t += step) {
        fair = s.update(0, 0.0372, 0.0370, t);
      }
      // (1 - g)^n with g = 1 - 2^(-dt/h) is 2^(-n dt/h): exactly one half after a half-life
      assertEquals(0.0371, fair, 1e-12, "step " + step);
    }
  }

  @Test
  void timeGoingBackwardsAndBadNumbersChangeNothing() {
    final FairValueSmoother s = new FairValueSmoother(1, 300.0);
    s.reset(0, 0.0371, 0.0370, 100 * SECOND);
    assertEquals(0.0371, s.update(0, 0.05, 0.0370, 50 * SECOND), 1e-15, "older stamp: no gain");
    assertEquals(0.0371, s.update(0, Double.NaN, 0.0370, 200 * SECOND), 1e-15);
    assertEquals(0.0, s.gain(0), 0.0);
    assertEquals(0.0, s.gain(-5), 0.0);
    assertTrue(Double.isNaN(s.update(0, 0.04, Double.NaN, 200 * SECOND)));
    assertEquals(1e-4, s.residual(0), 1e-15);
    assertThrows(IllegalArgumentException.class, () -> new FairValueSmoother(1, 0.0));
  }
}
