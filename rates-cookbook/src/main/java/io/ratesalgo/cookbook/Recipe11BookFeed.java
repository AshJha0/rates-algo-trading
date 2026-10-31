package io.ratesalgo.cookbook;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.venues.Venues;
import io.ratesalgo.venues.clob.ClobSimulator;
import io.ratesalgo.venues.feed.BookFeedConsumer;
import io.ratesalgo.venues.feed.InProcessFeedChannel;
import io.ratesalgo.venues.sim.Px;

/**
 * COOKBOOK.md recipe 11: subscribe to the CLOB's SBE book feed, rebuild the book, and recover
 * from a gap by snapshot.
 */
public final class Recipe11BookFeed {

  private Recipe11BookFeed() {}

  public static void main(final String[] args) {
    final int tenYear = 1007; // US91282CZG94
    final ManualClock clock = new ManualClock(Venues.T0);
    final InProcessFeedChannel feed = new InProcessFeedChannel();
    final ClobSimulator clob = (ClobSimulator) Venues.create("clob", clock, 1, feed);
    final BookFeedConsumer book = new BookFeedConsumer(clob.feed()); // the feed is also the snapshot service

    clob.submitInternal("DLR-A", tenYear, true, Px.price("97.984375"), 5_000_000, 0);
    clob.submitInternal("DLR-B", tenYear, false, Px.price("98.015625"), 3_000_000, 0);
    feed.poll(book, 100);
    print("after 2 orders", book, tenYear);

    feed.dropNext(2); // lose two frames on the wire
    clob.submitInternal("DLR-C", tenYear, true, Px.price("98"), 2_000_000, 0);
    clob.submitInternal("DLR-D", tenYear, false, Px.price("98.0078125"), 4_000_000, 0);
    clob.submitInternal("DLR-E", tenYear, true, Px.price("97.984375"), 1_000_000, 0);
    feed.poll(book, 100);
    print("after a gap", book, tenYear);
    System.out.println("gaps " + book.gaps() + ", recoveries " + book.recoveries()
        + ", venue sequence " + clob.feed().lastSequence() + ", consumer sequence " + book.lastSequence());
  }

  private static void print(final String label, final BookFeedConsumer book, final int instrument) {
    System.out.println(label + ": bids " + levels(book, instrument, Side.Buy) + " offers "
        + levels(book, instrument, Side.Sell));
  }

  private static String levels(final BookFeedConsumer book, final int instrument, final Side side) {
    final StringBuilder sb = new StringBuilder("[");
    book.levels(instrument, side).forEach((price, qty) ->
        sb.append(sb.length() == 1 ? "" : ", ").append(Px.text(price)).append('x').append(qty));
    return sb.append(']').toString();
  }
}
