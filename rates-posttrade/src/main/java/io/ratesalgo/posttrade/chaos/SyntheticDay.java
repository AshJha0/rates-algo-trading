package io.ratesalgo.posttrade.chaos;

import io.ratesalgo.messages.sbe.BustReason;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.QuoteStatus;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TradeSource;
import io.ratesalgo.posttrade.recon.ReconItem;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.ToLongFunction;

/**
 * A synthetic trading day with injected faults, written to a journal (API_SPEC.md Section 17.8,
 * COOKBOOK.md recipe 20).
 *
 * <p>Two bond instruments trade by order on venue 1 and one swap trades by RFQ on venue 2. The
 * venue's own record of the day (its drop copy) is produced alongside. Faults injected into our
 * journal, each on distinct fills chosen by the seeded generator:
 *
 * <ul>
 *   <li>duplicate fills: the same execution report or quote response delivered twice;
 *   <li>late fills within the day: delivered half an hour after their transaction time, after
 *       later fills;
 *   <li>late fills after the cut: delivered after the reconciliation cut;
 *   <li>busts: the venue busts a fill before the cut; the first bust arrives before its fill;
 *   <li>busts after the cut;
 *   <li>duplicate lines in the venue's drop copy.
 * </ul>
 *
 * <p>Generation depends only on the seed and the parameters.
 */
public final class SyntheticDay {

  /** Instruments and venues of the synthetic day. */
  public static final int BOND_A = 1001;

  public static final int BOND_B = 1002;
  public static final int SWAP = 2001;
  public static final int CLOB_VENUE = 1;
  public static final int RFQ_VENUE = 2;

  /**
   * Sizes of the day and of each fault.
   *
   * @param orders orders on the CLOB venue
   * @param rfqs RFQs on the RFQ venue
   * @param duplicates fills delivered twice
   * @param lateWithinDay fills delivered out of order before the cut
   * @param lateAfterCut fills delivered after the cut
   * @param busts fills busted before the cut
   * @param bustsAfterCut fills busted after the cut
   * @param dropCopyDuplicates drop copy lines sent twice
   */
  public record Params(int orders, int rfqs, int duplicates, int lateWithinDay, int lateAfterCut,
      int busts, int bustsAfterCut, int dropCopyDuplicates) {
    /** The day used by the acceptance test and recipe 20. */
    public static final Params DEFAULT = new Params(60, 40, 5, 2, 3, 3, 1, 2);
  }

  /**
   * What was generated.
   *
   * @param journal the journal file
   * @param date the business date
   * @param cutSequence last journal sequence at or before the cut
   * @param frames frames written
   * @param fills fills the venue executed
   * @param dropCopy the venue's drop copy at end of day, duplicates included
   * @param injected a one-line summary of the injected faults
   */
  public record Result(Path journal, LocalDate date, long cutSequence, long frames, int fills,
      List<ReconItem> dropCopy, String injected) {
    public Result {
      dropCopy = List.copyOf(dropCopy);
    }
  }

  /** The business date of the synthetic day: Friday 2026-10-30. */
  public static final LocalDate DATE = LocalDate.of(2026, 10, 30);

  private static final long SECOND = 1_000_000_000L;
  private static final long MINUTE = 60 * SECOND;
  private static final long HOUR = 60 * MINUTE;

  /** Midnight UTC of {@link #DATE}, in epoch nanoseconds. */
  public static final long MIDNIGHT = DATE.atStartOfDay(ZoneOffset.UTC).toEpochSecond() * SECOND;

  /** The reconciliation cut, 17:00 UTC. */
  public static final long CUT = MIDNIGHT + 17 * HOUR;

  private SyntheticDay() {}

  private interface Writer {
    void write(JournalScript s);
  }

  private static final class Event {
    private long arrival;
    private final int order;
    private final Writer writer;
    private final Fill fill;

    Event(final long arrival, final int order, final Writer writer, final Fill fill) {
      this.arrival = arrival;
      this.order = order;
      this.writer = writer;
      this.fill = fill;
    }
  }

  private record Fill(TradeSource source, int venueId, long tradeId, long venueOrderId, int instrumentId,
      Side side, long price, long quantity, long transact) {
    ReconItem item() {
      return new ReconItem(venueId + (source == TradeSource.Execution ? ":E:" : ":R:") + tradeId,
          instrumentId, side, quantity, price, transact);
    }
  }

  /** Generates the day into {@code journal}, which must not exist. */
  public static Result generate(final Path journal, final long seed, final Params p) {
    final SplittableRandom rnd = new SplittableRandom(seed);
    final List<Event> events = new ArrayList<>();
    final List<Event> fillEvents = new ArrayList<>();
    final int[] order = {0};
    long execId = 800_000;
    final long open = MIDNIGHT + 9 * HOUR;
    final long span = 7 * HOUR;

    for (int i = 0; i < p.orders(); i++) {
      final long t = open + span * i / Math.max(1, p.orders());
      final long clOrdId = 1_000L + i;
      final long venueOrderId = 500_000L + i;
      final int instrument = i % 2 == 0 ? BOND_A : BOND_B;
      final Side side = rnd.nextBoolean() ? Side.Buy : Side.Sell;
      final long price = 99_500_000_000L + rnd.nextInt(64) * 31_250_000L;
      final long qty = (1 + rnd.nextInt(10)) * 100_000_000L;
      events.add(new Event(t, order[0]++, s -> s.newOrder(t, CLOB_VENUE, clOrdId, instrument, side, price, qty), null));
      final long ackId = execId++;
      events.add(new Event(t + 1_000_000, order[0]++, s -> s.executionReport(t + 1_000_000, CLOB_VENUE,
          clOrdId, venueOrderId, ackId, instrument, side, OrdStatus.New, 0, 0, qty, 0, t + 1_000_000), null));
      if (rnd.nextBoolean()) {
        final long id = execId++;
        final long tx = t + 2_000_000;
        final Fill f = new Fill(TradeSource.Execution, CLOB_VENUE, id, venueOrderId, instrument, side, price, qty, tx);
        fillEvents.add(addEvent(events, order, tx, f, s -> s.executionReport(tx, CLOB_VENUE, clOrdId,
            venueOrderId, id, instrument, side, OrdStatus.Filled, price, qty, 0, qty, tx)));
      } else {
        final long half = qty / 2;
        final long id1 = execId++;
        final long id2 = execId++;
        final long tx1 = t + 2_000_000;
        final long tx2 = t + 3_000_000;
        final Fill f1 = new Fill(TradeSource.Execution, CLOB_VENUE, id1, venueOrderId, instrument, side, price, half, tx1);
        final Fill f2 = new Fill(TradeSource.Execution, CLOB_VENUE, id2, venueOrderId, instrument, side, price,
            qty - half, tx2);
        fillEvents.add(addEvent(events, order, tx1, f1, s -> s.executionReport(tx1, CLOB_VENUE, clOrdId,
            venueOrderId, id1, instrument, side, OrdStatus.PartiallyFilled, price, half, qty - half, half, tx1)));
        fillEvents.add(addEvent(events, order, tx2, f2, s -> s.executionReport(tx2, CLOB_VENUE, clOrdId,
            venueOrderId, id2, instrument, side, OrdStatus.Filled, price, qty - half, 0, qty, tx2)));
      }
    }

    for (int j = 0; j < p.rfqs(); j++) {
      final long t = open + 7 * MINUTE + span * j / Math.max(1, p.rfqs());
      final long rfqId = 9_000L + j;
      final long quoteId = 70_000L + j;
      final Side clientSide = rnd.nextBoolean() ? Side.Buy : Side.Sell;
      final long qty = (5 + rnd.nextInt(46)) * 1_000_000_000L;
      final long mid = 4_200_000_000L + rnd.nextInt(100) * 1_000_000L;
      final long bid = mid - 500_000;
      final long ask = mid + 500_000;
      final boolean done = rnd.nextBoolean();
      final int client = 300 + rnd.nextInt(20);
      events.add(new Event(t, order[0]++, s -> s.quoteRequest(t, RFQ_VENUE, rfqId, client, SWAP, clientSide, qty, "USD"), null));
      events.add(new Event(t + 50_000_000, order[0]++, s -> s.quote(t + 50_000_000, RFQ_VENUE, rfqId, quoteId,
          SWAP, bid, ask, qty, t + 10 * SECOND), null));
      final long tx = t + 2 * SECOND;
      if (done) {
        // the client buys at our ask, so we sell; or the client sells at our bid, so we buy
        final long px = clientSide == Side.Buy ? ask : bid;
        final Side ours = clientSide == Side.Buy ? Side.Sell : Side.Buy;
        final Fill f = new Fill(TradeSource.Rfq, RFQ_VENUE, quoteId, 0, SWAP, ours, px, qty, tx);
        fillEvents.add(addEvent(events, order, tx, f, s -> s.quoteResponse(tx, RFQ_VENUE, rfqId, quoteId,
            QuoteStatus.Done, px, qty, tx)));
      } else {
        events.add(new Event(tx, order[0]++, s -> s.quoteResponse(tx, RFQ_VENUE, rfqId, quoteId,
            QuoteStatus.TradedAway, 0, 0, tx), null));
      }
    }

    // Pick distinct fills for each fault.
    final List<Event> pool = new ArrayList<>(fillEvents);
    final List<Event> dups = take(pool, rnd, p.duplicates());
    final List<Event> lateIn = take(pool, rnd, p.lateWithinDay());
    final List<Event> lateOut = take(pool, rnd, p.lateAfterCut());
    final List<Event> busts = take(pool, rnd, p.busts());
    final List<Event> bustsOut = take(pool, rnd, p.bustsAfterCut());

    final Set<Fill> busted = new HashSet<>();
    for (final Event e : dups) {
      events.add(new Event(e.arrival + 10 * SECOND, order[0]++, e.writer, null));
    }
    for (final Event e : lateIn) {
      e.arrival += 30 * MINUTE;
    }
    int k = 0;
    for (final Event e : lateOut) {
      e.arrival = CUT + 30 * MINUTE + k++ * SECOND;
    }
    boolean first = true;
    for (final Event e : busts) {
      final Fill f = e.fill;
      final long bustAt = e.arrival + 5 * MINUTE;
      if (first) {
        e.arrival += 10 * MINUTE; // the bust reaches us before the fill
        first = false;
      }
      events.add(new Event(bustAt, order[0]++, s -> s.bust(bustAt, f.venueId(), f.source(), f.tradeId(),
          f.venueOrderId(), f.instrumentId(), BustReason.VenueError), null));
      busted.add(f);
    }
    k = 0;
    for (final Event e : bustsOut) {
      final Fill f = e.fill;
      final long bustAt = CUT + HOUR + k++ * SECOND;
      events.add(new Event(bustAt, order[0]++, s -> s.bust(bustAt, f.venueId(), f.source(), f.tradeId(),
          f.venueOrderId(), f.instrumentId(), BustReason.VenueError), null));
      busted.add(f);
    }

    final List<ReconItem> dropCopy = new ArrayList<>();
    for (final Event e : fillEvents) {
      if (!busted.contains(e.fill)) {
        dropCopy.add(e.fill.item());
      }
    }
    dropCopy.sort(Comparator.comparingLong(ReconItem::transactNanos).thenComparing(ReconItem::matchKey));
    final int fills = dropCopy.size();
    for (int d = 0; d < p.dropCopyDuplicates() && d < fills; d++) {
      dropCopy.add(dropCopy.get(rnd.nextInt(fills)));
    }

    final ToLongFunction<Event> arrival = e -> e.arrival;
    events.sort(Comparator.comparingLong(arrival).thenComparingInt(e -> e.order));
    long cutSequence = 0;
    long frames = 0;
    try (JournalScript script = new JournalScript(journal, 1 << 22, MIDNIGHT)) {
      for (final Event e : events) {
        final long before = script.nextSequence();
        e.writer.write(script);
        frames++;
        if (e.arrival <= CUT) {
          cutSequence = before;
        }
      }
    }
    final String injected = "duplicates=" + p.duplicates() + " late-within-day=" + p.lateWithinDay()
        + " late-after-cut=" + p.lateAfterCut() + " busts=" + p.busts() + " busts-after-cut="
        + p.bustsAfterCut() + " drop-copy-duplicates=" + p.dropCopyDuplicates();
    return new Result(journal, DATE, cutSequence, frames, fillEvents.size(), dropCopy, injected);
  }

  private static Event addEvent(final List<Event> events, final int[] order, final long at, final Fill f,
      final Writer w) {
    final Event e = new Event(at, order[0]++, w, f);
    events.add(e);
    return e;
  }

  private static List<Event> take(final List<Event> pool, final SplittableRandom rnd, final int n) {
    final List<Event> out = new ArrayList<>();
    for (int i = 0; i < n && !pool.isEmpty(); i++) {
      out.add(pool.remove(rnd.nextInt(pool.size())));
    }
    return out;
  }
}
