package io.ratesalgo.venues.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.sim.BookModel;
import io.ratesalgo.sim.MarketSpec;
import io.ratesalgo.sim.SessionSeeds;
import io.ratesalgo.sim.SimInstrument;
import io.ratesalgo.sim.SimParams;
import io.ratesalgo.venues.Venues;
import io.ratesalgo.venues.clob.ClobSimulator;
import io.ratesalgo.venues.feed.FeedChannel;
import io.ratesalgo.venues.feed.InProcessFeedChannel;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import quickfix.Message;
import quickfix.SessionID;

/**
 * Two orders for one Treasury reach the two venues that share its book in the same step. Which
 * venue's FIX thread delivers first is the operating system's choice. With a sequencer both
 * deliveries only queue: nothing touches the shared model until the owner drains, and it then
 * handles venue 1 before venue 2 whichever arrived first.
 *
 * <p>What this does not show. The defect was a data race: two FIX threads inside the shared
 * {@code BookModel} at once, without a lock. Handled one after the other, in either order, these
 * two orders leave the same market even without a sequencer (measured while writing this test),
 * so no single-threaded test fails on the old code; a race cannot be made to fail on demand. The
 * tests here and in {@link SimSequencerTest} assert the structure that makes the race impossible:
 * FIX threads queue and one thread handles.
 *
 * <p>The FIX threads are played by this test's thread calling the venues' QuickFIX/J callbacks.
 */
class SimV2SequencedVenuesTest {

  /** 2026-11-02T12:00:00Z, 07:00 in New York. */
  private static final long OPEN = 1_793_620_800_000_000_000L;
  private static final long SECOND = 1_000_000_000L;
  private static final String DESK = "RALGO";

  /** A marketable buy through every provider level of this venue's share of the book. */
  private static Message sweep(final long clOrdId, final Instrument instrument, final BookModel book) {
    final Message m = new Message();
    m.getHeader().setString(35, "D");
    m.setString(11, Long.toString(clOrdId));
    m.setString(48, instrument.securityId());
    m.setString(54, "1");
    m.setString(40, "2");
    m.setString(59, "3");
    m.setString(44, Px.text(SimV2Session.mantissa(book.levelPrice(false, 4))));
    double all = 0;
    for (int l = 0; l < 5; l++) {
      all += book.targetUnits(l);
    }
    m.setString(38, Long.toString((long) all));
    return m;
  }

  /**
   * Opens a session, runs it for a minute, delivers one sweep to each Treasury venue in the given
   * order and returns what the market looks like afterwards: both venues' logs and the shared
   * book's state.
   */
  private static String marketAfter(final boolean sequenced, final boolean secondVenueDeliversFirst) {
    final MarketSpec spec = new MarketSpec(SimParams.loadDefault());
    final ManualClock clock = new ManualClock(OPEN);
    final List<FeedChannel> feeds = new ArrayList<>();
    for (int v = 0; v <= spec.params().treasuryVenues(); v++) {
      feeds.add(new InProcessFeedChannel());
    }
    final SimSequencer sequencer = sequenced ? new SimSequencer() : null;
    final Venues.SimV2 v =
        Venues.simV2(SessionSeeds.session(spec, SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 90_000)), clock, feeds, sequencer);
    v.startFlow();
    for (int s = 0; s < 60; s++) {
      clock.advance(SECOND);
      v.onTime();
    }
    final Instrument instrument = v.session().instruments(SimInstrument.Kind.TREASURY).get(0);
    final BookModel book = v.session().model().book(v.session().index(instrument.securityId()));
    final ClobSimulator first = v.treasuryBooks().get(0);
    final ClobSimulator second = v.treasuryBooks().get(1);
    final Message toFirst = sweep(1, instrument, book);
    final Message toSecond = sweep(2, instrument, book);
    final Runnable deliverFirst = () -> first.fromApp(toFirst, new SessionID("FIX.4.4", first.compId(), DESK));
    final Runnable deliverSecond = () -> second.fromApp(toSecond, new SessionID("FIX.4.4", second.compId(), DESK));
    if (secondVenueDeliversFirst) {
      deliverSecond.run();
      deliverFirst.run();
    } else {
      deliverFirst.run();
      deliverSecond.run();
    }
    if (sequenced) {
      assertTrue(first.executions(DESK).isEmpty() && second.executions(DESK).isEmpty(), "nothing is handled before the drain");
      assertEquals(2, sequencer.drain());
    }
    assertTrue(first.executions(DESK).size() > 0 && second.executions(DESK).size() > 0, "both sweeps traded");
    final StringBuilder market = new StringBuilder();
    market.append(first.eventLog()).append('|').append(second.eventLog()).append('|')
        .append(Double.doubleToRawLongBits(book.transientDisplacement())).append('|')
        .append(Double.doubleToRawLongBits(book.ownDayVolume())).append('|')
        .append(Double.doubleToRawLongBits(book.bestBid())).append('|')
        .append(Double.doubleToRawLongBits(book.bestAsk()));
    for (int l = 0; l < 5; l++) {
      market.append('|').append(Double.doubleToRawLongBits(book.displayedUnits(true, l)))
          .append('|').append(Double.doubleToRawLongBits(book.displayedUnits(false, l)));
    }
    return market.toString();
  }

  @Test
  void withASequencerTheMarketDoesNotDependOnWhichVenueDeliveredFirst() {
    assertEquals(marketAfter(true, false), marketAfter(true, true));
  }
}
