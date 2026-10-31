package io.ratesalgo.cookbook;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.fix.codec.FixParser;
import io.ratesalgo.fix.codec.FixTags;
import io.ratesalgo.fix.codec.MsgType;
import io.ratesalgo.venues.Venues;
import io.ratesalgo.venues.d2c.ClientDecision;
import io.ratesalgo.venues.d2c.D2cRfqSimulator;
import io.ratesalgo.venues.d2c.RfqSpec;
import io.ratesalgo.venues.feed.InProcessFeedChannel;
import io.ratesalgo.venues.sim.EngineSession;
import io.ratesalgo.venues.sim.Px;
import io.ratesalgo.venues.sim.VenueHarness;
import java.util.ArrayList;
import java.util.List;

/**
 * COOKBOOK.md recipe 10: start the D2C RFQ simulator, connect an engine-side FIX 4.4 session,
 * and quote one RFQ by hand.
 */
public final class Recipe10VenueSession {

  private static final int[] SHOWN = {35, 131, 117, 54, 38, 9501, 297, 694, 44, 9502, 150, 39, 31, 32};

  private Recipe10VenueSession() {}

  public static void main(final String[] args) {
    final String isin = "US91282CZG94"; // on-the-run 10-year in data/refdata/v1 on 2026-11-02
    final ManualClock clock = new ManualClock(Venues.T0);
    final D2cRfqSimulator venue =
        (D2cRfqSimulator) Venues.create("d2c", clock, 7, new InProcessFeedChannel());
    final List<String> received = new ArrayList<>();
    try (VenueHarness harness = new VenueHarness(venue, clock);
        EngineSession engine = harness.connect("RALGO", (s, m, possDup) -> received.add(show(m)))) {
      System.out.println("session " + engine.session().state());

      // A client buys 10mm; three simulated dealers offer 98.03, 98.02 and 98.05.
      final long rfq =
          venue.injectRfq(
              new RfqSpec(isin, true, 10_000_000, 4,
                  List.of(Px.price("98.03"), Px.price("98.02"), Px.price("98.05")),
                  2003, ClientDecision.BEST, false));
      engine.quiesce();

      // Quote by hand: 97.99 bid, 98.015 offer, 10mm each side.
      engine.session().begin(MsgType.QUOTE)
          .put(FixTags.QUOTE_REQ_ID, rfq)
          .put(FixTags.QUOTE_ID, 1)
          .putText(FixTags.SYMBOL, isin)
          .putText(FixTags.SECURITY_ID, isin)
          .putChar(FixTags.SECURITY_ID_SOURCE, (byte) '4')
          .putDecimal(FixTags.BID_PX, Px.price("97.99"), 9)
          .putDecimal(FixTags.OFFER_PX, Px.price("98.015"), 9)
          .put(FixTags.BID_SIZE, 10_000_000)
          .put(FixTags.OFFER_SIZE, 10_000_000);
      engine.session().send();
      engine.quiesce();

      venue.decide(rfq); // the client trades with the best offer
      engine.quiesce();

      received.forEach(System.out::println);
      System.out.println("sequence errors " + engine.session().stats().sequenceErrors());
    }
  }

  private static String show(final FixParser m) {
    final StringBuilder sb = new StringBuilder();
    for (final int tag : SHOWN) {
      final int i = m.indexOf(tag);
      if (i >= 0) {
        sb.append(sb.length() == 0 ? "" : " ").append(tag).append('=').append(m.getStringAt(i));
      }
    }
    return sb.toString();
  }
}
