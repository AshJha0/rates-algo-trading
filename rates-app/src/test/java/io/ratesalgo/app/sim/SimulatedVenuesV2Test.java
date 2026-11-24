package io.ratesalgo.app.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.sim.SessionSeeds;
import io.ratesalgo.venues.clob.ClobSimulator;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The simulator v2 venues as the trading process will start them: listening on their own ports,
 * sharing one session, running from the driver's clock whatever its step.
 */
class SimulatedVenuesV2Test {

  private static final long SECOND = 1_000_000_000L;

  private static long[] run(final long stepNanos) {
    final SimClock clock = new SimClock(1_793_624_400_000_000_000L);
    try (SimulatedVenuesV2 venues = new SimulatedVenuesV2(clock, SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 90_000))) {
      final Set<Integer> ports = new HashSet<>();
      ports.add(venues.d2c().port());
      for (final ClobSimulator c : venues.books()) {
        ports.add(c.port());
      }
      assertEquals(4, ports.size());
      assertEquals(3, venues.books().size());
      assertEquals(2, venues.treasuryBooks().size());
      assertEquals(clock, venues.clock());
      assertEquals(0L, venues.session().sessionNanos());
      assertNotEquals(venues.feed(0), venues.feed(2));

      venues.startFlow();
      for (long done = 0; done < 900 * SECOND; done += stepNanos) {
        venues.advance(clock, stepNanos);
        venues.onTime();
      }
      // onto a whole second so that every step size has processed the same grid steps
      venues.advance(clock, 902 * SECOND - venues.session().sessionNanos());
      venues.onTime();
      assertEquals(902 * SECOND, venues.session().sessionNanos());
      return new long[] {
        venues.d2c().rfqCount(),
        venues.treasuryBooks().get(0).trades(),
        venues.treasuryBooks().get(1).trades(),
        venues.futuresBook().trades()
      };
    }
  }

  @Test
  void venuesRunFromTheDriversClockWhateverItsStep() {
    final long[] coarse = run(250_000_000L);
    final long[] fine = run(40_000_000L);
    assertTrue(coarse[1] > 0 && coarse[2] > 0 && coarse[3] > 0, java.util.Arrays.toString(coarse));
    for (int i = 0; i < coarse.length; i++) {
      assertEquals(coarse[i], fine[i]);
    }
  }
}
