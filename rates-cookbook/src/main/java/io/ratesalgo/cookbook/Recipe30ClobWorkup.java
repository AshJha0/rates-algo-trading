package io.ratesalgo.cookbook;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.venues.Venues;
import io.ratesalgo.venues.clob.ClobBook;
import io.ratesalgo.venues.clob.ClobConfig;
import io.ratesalgo.venues.clob.ClobSimulator;
import io.ratesalgo.venues.feed.BookFeedConsumer;
import io.ratesalgo.venues.feed.InProcessFeedChannel;
import io.ratesalgo.venues.sim.Px;
import java.util.List;

/** COOKBOOK.md recipe 30: run the CLOB simulator through a workup and read the book feed. */
public final class Recipe30ClobWorkup {

  private Recipe30ClobWorkup() {}

  static final int TEN_YEAR = 1007; // US91282CZG94

  public static void main(final String[] args) {
    final ManualClock clock = new ManualClock(Venues.T0);
    final InProcessFeedChannel feed = new InProcessFeedChannel();
    final ClobSimulator clob = (ClobSimulator) Venues.create("clob", clock, 1, feed);
    final BookFeedConsumer book = new BookFeedConsumer(clob.feed());
    final ClobConfig cfg = clob.config();
    System.out.println("workup " + cfg.workupNanos() / 1_000_000 + " ms, exclusive " + cfg.exclusiveNanos() / 1_000_000
        + " ms, extend on trade " + cfg.extendOnTrade());
    final long p = Px.price("98.015625");
    final int[] cursor = {0};
    final long ms = 1_000_000L;

    clob.submitInternal("DLR-A", TEN_YEAR, false, p, 10_000_000, 0);
    clob.submitInternal("DLR-A", TEN_YEAR, false, Px.price("98.03125"), 5_000_000, 0);
    clob.submitInternal("DLR-Z", TEN_YEAR, true, Px.price("97.984375"), 4_000_000, 0);
    step("resting book", clob, clock, feed, book, 0, cursor);

    clob.submitInternal("DLR-B", TEN_YEAR, true, p, 2_000_000, 0); // lifts the offer: a workup opens at p
    step("B lifts 2m at 98.015625", clob, clock, feed, book, 0, cursor);

    clob.submitInternal("DLR-C", TEN_YEAR, true, p, 3_000_000, 0); // an outsider: held in the exclusive window
    clob.submitInternal("DLR-B", TEN_YEAR, true, p, 1_000_000, 0); // a participant: trades at once
    step("C bids 3m (held), B adds 1m", clob, clock, feed, book, 100 * ms, cursor);

    clob.submitInternal("DLR-D", TEN_YEAR, true, Px.price("98.03125"), 1_000_000, 0); // an outsider bidding above p: held too
    step("exclusive window over", clob, clock, feed, book, cfg.exclusiveNanos(), cursor);

    step("workup timer expired", clob, clock, feed, book, cfg.workupNanos(), cursor);
    System.out.println("trades " + clob.trades() + ", feed sequence " + clob.feed().lastSequence()
        + ", consumer sequence " + book.lastSequence() + ", gaps " + book.gaps());
  }

  private static void step(final String label, final ClobSimulator clob, final ManualClock clock,
      final InProcessFeedChannel feed, final BookFeedConsumer book, final long advanceNanos, final int[] logCursor) {
    if (advanceNanos > 0) {
      clock.advance(advanceNanos);
      clob.onTime(); // runs the workup timers that fall due
    }
    feed.poll(book, 100);
    final ClobBook b = clob.book(TEN_YEAR);
    System.out.println(label + ": " + (b.inWorkup() ? "workup at " + Px.text(b.workupPrice()) : "normal")
        + ", held " + b.held().size());
    final List<String> log = clob.eventLog();
    for (; logCursor[0] < log.size(); logCursor[0]++) {
      final String line = log.get(logCursor[0]);
      final String text = line.substring(line.indexOf(' ') + 1); // drop the timestamp
      if (text.startsWith("trade")) {
        System.out.println("  " + text);
      }
    }
    System.out.println("  feed bids " + levels(book, Side.Buy) + " offers " + levels(book, Side.Sell));
  }

  private static String levels(final BookFeedConsumer book, final Side side) {
    final StringBuilder sb = new StringBuilder("[");
    book.levels(TEN_YEAR, side).forEach((price, qty) ->
        sb.append(sb.length() == 1 ? "" : ", ").append(Px.text(price)).append('x').append(qty));
    return sb.append(']').toString();
  }
}
